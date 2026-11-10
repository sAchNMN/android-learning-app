package com.szc.learning

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.szc.learning.ui.login.LoginActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 登录链路仪器测试：真实请求后端，验证「登录 → 跳首页 → 展示用户信息」。
 *
 * 跑之前要保证：
 * 1. 后端在 8080 且可访问（模拟器侧用 `adb reverse tcp:8080 tcp:8080`，
 *    且 APK 以 `-PapiBaseUrl=http://127.0.0.1:8080/` 构建）
 * 2. 服务端验证码已关闭（`sys.account.captchaEnabled=false`），
 *    否则测试里填不出验证码
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {

    @Test
    fun login_withAdminAccount_landsOnHomeAndShowsUser() {
        ActivityScenario.launch(LoginActivity::class.java).use {
            onView(withId(R.id.usernameInput)).perform(replaceText("admin"))
            onView(withId(R.id.passwordInput))
                .perform(replaceText("admin123"), closeSoftKeyboard())
            onView(withId(R.id.loginButton)).perform(click())

            // 等 /login + /getInfo 两个请求和页面跳转
            Thread.sleep(5_000)

            onView(withId(R.id.nicknameText)).check(matches(withText("若依")))
            onView(withId(R.id.accountText)).check(matches(isDisplayed()))
            onView(withId(R.id.logoutButton)).check(matches(isDisplayed()))
        }
    }
}
