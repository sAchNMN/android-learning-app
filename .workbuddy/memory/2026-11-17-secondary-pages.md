<!-- 归档（2026-11-18 清理 worktree 时保存）
     来源：worktree master-9311190a 里未跟踪的同日文件，属 feat/refine-secondary-pages 分支的独立记录。
     与主工作区的 2026-11-17.md 是并行分支各自写的，内容不重复，故单列而不合并。 -->
# 2026-11-17

## 细化二级/三级页面（分支 feat/refine-secondary-pages，commit 3aa8f9b）

二级页 `ShopListActivity`（店铺列表）
- 顶栏返回 + 搜索（店名/分类/简介，本地过滤）
- 分类芯片按 `/app/shop/list` 返回的 category 去重生成，「全部」= category null
- 排序：综合 / 评分 / 送达最快 / 起送最低（VM 里 `ShopSort` 枚举 + `visible` 计算属性）
- 空态三分：请求失败（重试）／无数据／筛不出来（清除筛选）
- `item_shop` 加首字头像（后端没有图片）、评分、送达时长、地址

三级页 `ShopDetailActivity`（店铺详情）
- `ConcatAdapter(ShopHeaderAdapter, DishRowAdapter)`：信息卡作为列表第一项，整体可滚
- `DishRow` sealed interface（Section / Item）单适配器两种 viewType，DiffUtil 只重绘变化行
- 菜品行加 `minusButton`（原来 VM 有 removeDish 但 UI 没接）
- 购物车明细移到 `CartSheetDialog`（`BottomSheetDialog` + ListAdapter）
- 底部栏 `LinearProgressIndicator` 起送进度；`loadError` 整页重试、`actionError` 一次性 toast
- 结算对话框用 `setOnShowListener` 接管确定按钮做校验（手机号 11 位），校验不过不关对话框

## 关键取舍
- **没有引入新依赖**：`androidx.swiperefreshlayout` 未在 gradle 缓存里，沙箱大概率下不下来，改为错误态重试按钮。以后要下拉刷新再补。
- 筛选/排序放本地，因为店铺就三条；注释里写清了「量大了再换服务端分页」。
- 金额 `formatMoney` 固定 `Locale.CHINA`（lint DefaultLocale）；新文案全走 `strings.xml`（lint SetTextI18n）。

## 本机操作坑（新增）
- **`VAR=... ; cmd` 形式会触发沙箱报错** `sandbox-center cmd decisionRecord missing actual resource subject`。必须直接写绝对路径调用 adb，不经过 shell 变量。
- `MSYS_NO_PATHCONV=1` 只作用于它所在的那一条命令，`pull /sdcard/x.png` 也必须单独加前缀，否则路径被改写成 `C:/.../PortableGit/sdcard/...`。
- 模拟器输入法是 Gboard 中文：`input text admin` 只进候选栏不落字，改密码框时还会被自动纠正成 administrator。`ime disable` 后可输入但一碰输入框又复活；最终靠 `settings put secure default_input_method` + 先点字段再 `input text` 拿到干净输入。
- **键盘收起时按 BACK 会直接干掉 Activity**（LoginActivity/列表页都是根/单页）。自动化脚本里别用 `keyevent 4` 当"收键盘"。
- 用 `adb reverse --remove tcp:8080` 可以直接造出网络错误态，比杀后端进程安全；验证完再 `reverse tcp:8080 tcp:8080`。
- 截图坐标要按 `wm size`（1280x2856）算，别照 1080p 的截图估。

## 合并回 master：改成「保留两边成果」（master 已到 6355e32）

**重大发现**：repo 有 3 个 worktree，`master` 被 `C:/Users/34759/Desktop/android-demo/android-learning-app` 占用，
那边有 35 项未提交改动 —— 是一整条**独立的开发线**：顾客端改成 `MainActivity` + 4 tab（首页/购物车/订单/我的）、
新增全局 `CartStore`、删掉 HomeActivity / ShopListActivity / OrderListActivity。我的分支还是 Activity 栈，
直接 merge 必冲突且编译不过。用户选了「保留两边成果」。

处理顺序（都在 Desktop worktree 里做）：
1. 先把那 35 项固化成一个 commit `623abf1`（他原本没提交，不固化就没法在上面叠改动）
2. 切 `feat/refine-fragment-pages`，手工移植而非 merge
3. `git checkout master && git merge --ff-only` → `6355e32`

移植映射（关键，下次别重复推导）：
| 我这边（Activity 栈） | master 侧（tab 架构） |
| --- | --- |
| ShopListActivity + activity_shop_list.xml | HomeFragment + fragment_home.xml（去掉 toolbar） |
| ShopDetailViewModel 本地 cart Map | 保留 CartStore 为唯一真源，只映射本店那份进 UiState |
| CartLine | 改用 CartStore 的 `CartItem` |
| item_cart.xml（明细行） | **改名 item_cart_line.xml**（master 已有同名的购物车 tab 卡片） |
| errorMessage | 拆成 loadError（整页重试）+ actionError（一次性 toast） |
| 下单后跳 OrderListActivity | 跳 MainActivity 的 tab_order |

`ShopDetailViewModel` 保留原有三参 factory(repository, cartStore, shopId) —— CartFragment 与 MainActivity
都依赖它，改签名会连锁。`formatMoney` 仍留在 ShopAdapter.kt（CartFragment 在引用）。

## 本次新踩的坑
- **不能对已检出别处的分支执行 checkout/merge**：`master` 被 Desktop worktree 占用，只能在那个目录里操作。
- 模拟器中途掉线后 `adb devices` 空、`tasklist` 查不到 qemu → **模拟器进程整个没了**，
  自己用 `emulator.exe` 起也活不过一次 Bash 调用（沙箱会杀子进程），只能让用户手动开。
- 点底部导航用估算坐标点不中：tab 的 bounds 是 `[320,2468][640,2712]`，估到 2444 就落到列表里。
  **先用 `uiautomator dump` 取 bounds 再点**，别按截图比例硬算。
