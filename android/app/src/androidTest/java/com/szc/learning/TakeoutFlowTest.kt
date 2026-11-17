package com.szc.learning

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.ui.login.LoginActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 外卖闭环仪器测试：登录 → 我的 → 首页 → 选店 → 加菜 → 结算 → 订单 tab。
 *
 * 跑之前要保证：
 * 1. 后端在 8080 且可访问（模拟器侧用 `adb reverse tcp:8080 tcp:8080`，
 *    APK 以 `-PapiBaseUrl=http://127.0.0.1:8080/` 构建）
 * 2. 服务端验证码已关闭（`sys.account.captchaEnabled=false`）
 * 3. 数据库里有 `sql/02_takeout_data.sql` 的店铺与菜品
 */
@RunWith(AndroidJUnit4::class)
class TakeoutFlowTest {

    @Test
    fun login_thenPlaceOrder_landsOnOrderTab() {
        ActivityScenario.launch(LoginActivity::class.java).use {
            // ---- 登录 ----
            onView(withId(R.id.usernameInput)).perform(replaceText("admin"))
            onView(withId(R.id.passwordInput))
                .perform(replaceText("admin123"), closeSoftKeyboard())
            onView(withId(R.id.loginButton)).perform(click())
            Thread.sleep(5_000)

            // ---- 我的 tab：账号信息 ----
            onView(withContentDescription("我的")).perform(click())
            onView(withId(R.id.nicknameText)).check(matches(withText("若依")))

            // ---- 首页 tab：点外卖 ----
            onView(withContentDescription("首页")).perform(click())
            Thread.sleep(3_000)

            onView(withText("老王牛肉面")).perform(click())
            Thread.sleep(3_000)

            // ---- 加两个菜 ----
            onView(withContentDescription("加入购物车：红烧牛肉面")).perform(click())
            Thread.sleep(500)
            onView(withContentDescription("加入购物车：卤蛋")).perform(click())
            Thread.sleep(800)

            // ---- 结算 ----
            onView(withId(R.id.checkoutButton)).perform(click())
            Thread.sleep(1_200)
            onView(withText("提交订单")).perform(click())
            Thread.sleep(5_000)

            // ---- 订单页 ----
            // 订单会累积（每跑一次多一条），所以不能按文本断言唯一性，
            // 直接检查列表条数并确认关键元素存在
            onView(withText("我的订单")).check(matches(isDisplayed()))
            onView(withId(R.id.orderRecycler)).check { view, noViewFound ->
                if (noViewFound != null) throw noViewFound
                val count = (view as RecyclerView).adapter?.itemCount ?: 0
                if (count <= 0) throw AssertionError("订单列表为空，下单没有落库")
            }
        }
    }
}
