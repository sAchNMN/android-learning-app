package com.szc.learning.feature.demo

import android.content.Context
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 贪吃蛇演示页。
 *
 * 整页内容由 [SnakeGameView] 自绘，Activity 只做三件事：
 * 恢复/保存最高分、把系统栏高度告诉视图、页面不可见时暂停。
 */
class SnakeDemoActivity : AppCompatActivity() {

    private lateinit var gameView: SnakeGameView

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_snake)

        gameView = findViewById(R.id.snake_view)
        gameView.bestScore = prefs.getInt(KEY_BEST, 0)

        gameView.onGameOver = { score ->
            val best = prefs.getInt(KEY_BEST, 0)
            if (score > best) {
                prefs.edit().putInt(KEY_BEST, score).apply()
                gameView.bestScore = score
            }
        }

        // 棋盘铺到屏幕边缘，但悬浮信息要与系统栏保持安全距离
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.snake_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            gameView.setContentInsets(bars.top, bars.bottom)
            insets
        }
    }

    override fun onPause() {
        super.onPause()
        gameView.pauseIfRunning()
    }

    private companion object {
        const val PREFS_NAME = "snake_prefs"
        const val KEY_BEST = "best_score"
    }
}
