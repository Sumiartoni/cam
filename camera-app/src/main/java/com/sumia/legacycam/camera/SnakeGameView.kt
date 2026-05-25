package com.sumia.legacycam.camera

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class SnakeGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Listener {
        fun onScoreChanged(score: Int, highScore: Int)
        fun onStatusChanged(status: String)
        fun onRunningChanged(running: Boolean)
    }

    private data class Cell(val x: Int, val y: Int)

    private enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val prefs = context.getSharedPreferences("snake_game", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = Random(System.currentTimeMillis())
    private val boardSize = 16
    private val snake = ArrayDeque<Cell>()
    private var food = Cell(0, 0)
    private var direction = Direction.RIGHT
    private var nextDirection = Direction.RIGHT
    private var score = 0
    private var highScore = prefs.getInt(KEY_HIGH_SCORE, 0)
    private var running = false
    private var paused = false
    private var gameOver = false
    private var listener: Listener? = null
    private var autoPausedByVisibility = false

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF09131B.toInt()
    }
    private val boardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.2f)
        color = 0x6638FFC4.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(0.6f)
        color = 0x1628FFD2.toInt()
    }
    private val snakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF36FFB7.toInt()
    }
    private val snakeHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9FFFF0.toInt()
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA00FFC3.toInt()
        maskFilter = BlurMaskFilter(context.dp(12f), BlurMaskFilter.Blur.NORMAL)
    }
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7A3D.toInt()
    }
    private val foodGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FF7A3D.toInt()
        maskFilter = BlurMaskFilter(context.dp(14f), BlurMaskFilter.Blur.NORMAL)
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA071118.toInt()
    }
    private val overlayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4FAFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = context.sp(18f)
    }
    private val overlaySubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8FC6BA.toInt()
        textAlign = Paint.Align.CENTER
        textSize = context.sp(13f)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running || paused || gameOver) {
                return
            }
            step()
            if (running && !paused && !gameOver) {
                mainHandler.postDelayed(this, currentTickDelay())
            }
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!running || gameOver) {
                    startNewGame()
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val diffX = e2.x - (e1?.x ?: e2.x)
                val diffY = e2.y - (e1?.y ?: e2.y)
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    if (diffX > 0) {
                        queueDirection(Direction.RIGHT)
                    } else {
                        queueDirection(Direction.LEFT)
                    }
                } else {
                    if (diffY > 0) {
                        queueDirection(Direction.DOWN)
                    } else {
                        queueDirection(Direction.UP)
                    }
                }
                return true
            }
        },
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        resetBoard()
        notifyScore()
        notifyStatus(context.getString(R.string.snake_status_ready))
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        notifyScore()
        notifyStatus(currentStatusText())
        listener?.onRunningChanged(running && !paused && !gameOver)
    }

    fun startNewGame() {
        resetBoard()
        running = true
        paused = false
        gameOver = false
        notifyStatus(context.getString(R.string.snake_status_running))
        listener?.onRunningChanged(true)
        scheduleNextTick(true)
        invalidate()
    }

    fun togglePause() {
        if (!running || gameOver) {
            startNewGame()
            return
        }
        paused = !paused
        if (paused) {
            mainHandler.removeCallbacks(tickRunnable)
            notifyStatus(context.getString(R.string.snake_status_paused))
        } else {
            notifyStatus(context.getString(R.string.snake_status_running))
            scheduleNextTick(true)
        }
        listener?.onRunningChanged(running && !paused && !gameOver)
        invalidate()
    }

    fun isPaused(): Boolean = paused

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(tickRunnable)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (autoPausedByVisibility && running && paused && !gameOver) {
                autoPausedByVisibility = false
                paused = false
                notifyStatus(context.getString(R.string.snake_status_running))
                listener?.onRunningChanged(true)
                scheduleNextTick(true)
                invalidate()
            }
            return
        }

        if (running && !paused && !gameOver) {
            autoPausedByVisibility = true
            paused = true
            mainHandler.removeCallbacks(tickRunnable)
            notifyStatus(context.getString(R.string.snake_status_paused))
            listener?.onRunningChanged(false)
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = width
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = context.dp(10f)
        val boardRect = RectF(padding, padding, width - padding, height - padding)

        boardPaint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xFF071118.toInt(), 0xFF0B1820.toInt(), 0xFF0C1E24.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(boardRect, context.dp(22f), context.dp(22f), boardPaint)
        canvas.drawRoundRect(boardRect, context.dp(22f), context.dp(22f), boardStrokePaint)

        val cellSize = boardRect.width() / boardSize
        drawGrid(canvas, boardRect, cellSize)
        drawFood(canvas, boardRect, cellSize)
        drawSnake(canvas, boardRect, cellSize)
        drawOverlay(canvas, boardRect)
    }

    private fun drawGrid(canvas: Canvas, boardRect: RectF, cellSize: Float) {
        for (index in 1 until boardSize) {
            val offset = boardRect.left + (index * cellSize)
            canvas.drawLine(offset, boardRect.top, offset, boardRect.bottom, gridPaint)
            canvas.drawLine(boardRect.left, boardRect.top + (index * cellSize), boardRect.right, boardRect.top + (index * cellSize), gridPaint)
        }
    }

    private fun drawFood(canvas: Canvas, boardRect: RectF, cellSize: Float) {
        val rect = cellRect(food, boardRect, cellSize, 0.2f)
        canvas.drawRoundRect(rect, context.dp(14f), context.dp(14f), foodGlowPaint)
        canvas.drawRoundRect(rect, context.dp(12f), context.dp(12f), foodPaint)
    }

    private fun drawSnake(canvas: Canvas, boardRect: RectF, cellSize: Float) {
        snake.forEachIndexed { index, cell ->
            val inset = if (index == 0) 0.12f else 0.18f
            val rect = cellRect(cell, boardRect, cellSize, inset)
            if (index == 0) {
                canvas.drawRoundRect(rect, context.dp(16f), context.dp(16f), glowPaint)
                canvas.drawRoundRect(rect, context.dp(13f), context.dp(13f), snakeHeadPaint)
            } else {
                canvas.drawRoundRect(rect, context.dp(12f), context.dp(12f), snakePaint)
            }
        }
    }

    private fun drawOverlay(canvas: Canvas, boardRect: RectF) {
        val title: String
        val subtitle: String
        when {
            !running -> {
                title = context.getString(R.string.snake_overlay_ready)
                subtitle = context.getString(R.string.snake_overlay_ready_hint)
            }
            paused -> {
                title = context.getString(R.string.snake_overlay_paused)
                subtitle = context.getString(R.string.snake_overlay_paused_hint)
            }
            gameOver -> {
                title = context.getString(R.string.snake_overlay_game_over)
                subtitle = context.getString(R.string.snake_overlay_game_over_hint)
            }
            else -> return
        }

        canvas.drawRoundRect(boardRect, context.dp(22f), context.dp(22f), overlayPaint)
        val centerX = boardRect.centerX()
        val centerY = boardRect.centerY()
        canvas.drawText(title, centerX, centerY - context.dp(8f), overlayTextPaint)
        canvas.drawText(subtitle, centerX, centerY + context.dp(18f), overlaySubTextPaint)
    }

    private fun cellRect(cell: Cell, boardRect: RectF, cellSize: Float, insetRatio: Float): RectF {
        val left = boardRect.left + (cell.x * cellSize)
        val top = boardRect.top + (cell.y * cellSize)
        val inset = cellSize * insetRatio
        return RectF(
            left + inset,
            top + inset,
            left + cellSize - inset,
            top + cellSize - inset,
        )
    }

    private fun resetBoard() {
        mainHandler.removeCallbacks(tickRunnable)
        snake.clear()
        snake.add(Cell(boardSize / 2, boardSize / 2))
        snake.add(Cell((boardSize / 2) - 1, boardSize / 2))
        snake.add(Cell((boardSize / 2) - 2, boardSize / 2))
        direction = Direction.RIGHT
        nextDirection = Direction.RIGHT
        score = 0
        placeFood()
        notifyScore()
    }

    private fun step() {
        direction = nextDirection
        val head = snake.first()
        val nextHead = when (direction) {
            Direction.UP -> Cell(head.x, head.y - 1)
            Direction.DOWN -> Cell(head.x, head.y + 1)
            Direction.LEFT -> Cell(head.x - 1, head.y)
            Direction.RIGHT -> Cell(head.x + 1, head.y)
        }

        if (nextHead.x !in 0 until boardSize || nextHead.y !in 0 until boardSize || snake.any { it == nextHead }) {
            running = false
            paused = false
            gameOver = true
            listener?.onRunningChanged(false)
            notifyStatus(context.getString(R.string.snake_status_game_over))
            invalidate()
            return
        }

        snake.addFirst(nextHead)
        if (nextHead == food) {
            score += 1
            if (score > highScore) {
                highScore = score
                prefs.edit().putInt(KEY_HIGH_SCORE, highScore).apply()
            }
            placeFood()
            notifyScore()
        } else {
            snake.removeLast()
        }

        invalidate()
    }

    private fun queueDirection(candidate: Direction) {
        if (!running || paused) {
            return
        }

        val illegalTurn = when (direction) {
            Direction.UP -> candidate == Direction.DOWN
            Direction.DOWN -> candidate == Direction.UP
            Direction.LEFT -> candidate == Direction.RIGHT
            Direction.RIGHT -> candidate == Direction.LEFT
        }
        if (!illegalTurn) {
            nextDirection = candidate
        }
    }

    private fun placeFood() {
        do {
            food = Cell(random.nextInt(boardSize), random.nextInt(boardSize))
        } while (snake.any { it == food })
    }

    private fun scheduleNextTick(immediate: Boolean = false) {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.postDelayed(tickRunnable, if (immediate) currentTickDelay() else currentTickDelay())
    }

    private fun currentTickDelay(): Long {
        return max(78L, 150L - (score * 4L))
    }

    private fun notifyScore() {
        listener?.onScoreChanged(score, highScore)
    }

    private fun notifyStatus(status: String) {
        listener?.onStatusChanged(status)
    }

    private fun currentStatusText(): String {
        return when {
            gameOver -> context.getString(R.string.snake_status_game_over)
            paused -> context.getString(R.string.snake_status_paused)
            running -> context.getString(R.string.snake_status_running)
            else -> context.getString(R.string.snake_status_ready)
        }
    }

    private fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

    private fun Context.sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private companion object {
        const val KEY_HIGH_SCORE = "snake_high_score"
    }
}
