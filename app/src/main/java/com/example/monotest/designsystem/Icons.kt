package com.example.monotest.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object MonoTestIcons {
    @Suppress("CheckReturnValue")
    public val person: ImageVector
        get() {
            if (_person != null) {
                return _person!!
            }
            _person =
                ImageVector.Builder(
                    name = "person",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(9.18f, 10.83f)
                            quadTo(8f, 9.65f, 8f, 8f)
                            reflectiveQuadTo(9.18f, 5.18f)
                            reflectiveQuadTo(12f, 4f)
                            reflectiveQuadToRelative(2.83f, 1.18f)
                            reflectiveQuadTo(16f, 8f)
                            reflectiveQuadToRelative(-1.17f, 2.82f)
                            reflectiveQuadTo(12f, 12f)
                            reflectiveQuadTo(9.18f, 10.83f)
                            close()
                            moveTo(4f, 20f)
                            verticalLineTo(17.2f)
                            quadTo(4f, 16.35f, 4.44f, 15.64f)
                            quadTo(4.88f, 14.93f, 5.6f, 14.55f)
                            quadTo(7.15f, 13.77f, 8.75f, 13.39f)
                            reflectiveQuadTo(12f, 13f)
                            reflectiveQuadToRelative(3.25f, 0.39f)
                            reflectiveQuadToRelative(3.15f, 1.16f)
                            quadToRelative(0.72f, 0.38f, 1.16f, 1.09f)
                            reflectiveQuadTo(20f, 17.2f)
                            verticalLineTo(20f)
                            horizontalLineTo(4f)
                            close()
                        }
                    }
                    .build()
            return _person!!
        }

    private var _person: ImageVector? = null

    @Suppress("CheckReturnValue")
    public val info: ImageVector
        get() {
            if (_info != null) {
                return _info!!
            }
            _info =
                ImageVector.Builder(
                    name = "info",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(11f, 17f)
                            horizontalLineToRelative(2f)
                            verticalLineTo(11f)
                            horizontalLineTo(11f)
                            verticalLineToRelative(6f)
                            close()
                            moveTo(12.71f, 8.71f)
                            quadTo(13f, 8.42f, 13f, 8f)
                            quadTo(13f, 7.57f, 12.71f, 7.29f)
                            reflectiveQuadTo(12f, 7f)
                            reflectiveQuadTo(11.29f, 7.29f)
                            reflectiveQuadTo(11f, 8f)
                            quadToRelative(0f, 0.42f, 0.29f, 0.71f)
                            reflectiveQuadTo(12f, 9f)
                            reflectiveQuadTo(12.71f, 8.71f)
                            close()
                            moveTo(12f, 22f)
                            quadTo(9.93f, 22f, 8.1f, 21.21f)
                            quadTo(6.28f, 20.43f, 4.93f, 19.08f)
                            quadTo(3.58f, 17.73f, 2.79f, 15.9f)
                            reflectiveQuadTo(2f, 12f)
                            quadTo(2f, 9.92f, 2.79f, 8.1f)
                            quadTo(3.58f, 6.27f, 4.93f, 4.93f)
                            quadTo(6.28f, 3.57f, 8.1f, 2.79f)
                            quadTo(9.93f, 2f, 12f, 2f)
                            reflectiveQuadToRelative(3.9f, 0.79f)
                            reflectiveQuadToRelative(3.17f, 2.14f)
                            quadToRelative(1.35f, 1.35f, 2.14f, 3.17f)
                            quadTo(22f, 9.92f, 22f, 12f)
                            reflectiveQuadToRelative(-0.79f, 3.9f)
                            reflectiveQuadToRelative(-2.14f, 3.17f)
                            quadToRelative(-1.35f, 1.35f, -3.17f, 2.14f)
                            reflectiveQuadTo(12f, 22f)
                            close()
                        }
                    }
                    .build()
            return _info!!
        }

    private var _info: ImageVector? = null

    @Suppress("CheckReturnValue")
    public val lock: ImageVector
        get() {
            if (_lock != null) {
                return _lock!!
            }
            _lock =
                ImageVector.Builder(
                    name = "lock",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(6f, 22f)
                            quadTo(5.18f, 22f, 4.59f, 21.41f)
                            reflectiveQuadTo(4f, 20f)
                            verticalLineTo(10f)
                            quadTo(4f, 9.17f, 4.59f, 8.59f)
                            reflectiveQuadTo(6f, 8f)
                            horizontalLineTo(7f)
                            verticalLineTo(6f)
                            quadTo(7f, 3.92f, 8.46f, 2.46f)
                            reflectiveQuadTo(12f, 1f)
                            reflectiveQuadToRelative(3.54f, 1.46f)
                            reflectiveQuadTo(17f, 6f)
                            verticalLineTo(8f)
                            horizontalLineToRelative(1f)
                            quadToRelative(0.82f, 0f, 1.41f, 0.59f)
                            reflectiveQuadTo(20f, 10f)
                            verticalLineTo(20f)
                            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                            reflectiveQuadTo(18f, 22f)
                            horizontalLineTo(6f)
                            close()
                            moveToRelative(7.41f, -5.59f)
                            quadTo(14f, 15.83f, 14f, 15f)
                            reflectiveQuadTo(13.41f, 13.59f)
                            reflectiveQuadTo(12f, 13f)
                            reflectiveQuadToRelative(-1.41f, 0.59f)
                            quadTo(10f, 14.18f, 10f, 15f)
                            reflectiveQuadToRelative(0.59f, 1.41f)
                            reflectiveQuadTo(12f, 17f)
                            reflectiveQuadToRelative(1.41f, -0.59f)
                            close()
                            moveTo(9f, 8f)
                            horizontalLineToRelative(6f)
                            verticalLineTo(6f)
                            quadTo(15f, 4.75f, 14.13f, 3.88f)
                            reflectiveQuadTo(12f, 3f)
                            reflectiveQuadTo(9.88f, 3.88f)
                            reflectiveQuadTo(9f, 6f)
                            verticalLineTo(8f)
                            close()
                        }
                    }
                    .build()
            return _lock!!
        }

    private var _lock: ImageVector? = null

    @Suppress("CheckReturnValue")
    public val keyboard_arrow_up: ImageVector
        get() {
            if (_keyboard_arrow_up != null) {
                return _keyboard_arrow_up!!
            }
            _keyboard_arrow_up =
                ImageVector.Builder(
                    name = "keyboard_arrow_up",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(12f, 10.8f)
                            lineTo(7.4f, 15.4f)
                            lineTo(6f, 14f)
                            lineTo(12f, 8f)
                            lineToRelative(6f, 6f)
                            lineToRelative(-1.4f, 1.4f)
                            lineTo(12f, 10.8f)
                            close()
                        }
                    }
                    .build()
            return _keyboard_arrow_up!!
        }

    private var _keyboard_arrow_up: ImageVector? = null

    @Suppress("CheckReturnValue")
    public val keyboard_arrow_down: ImageVector
        get() {
            if (_keyboard_arrow_down != null) {
                return _keyboard_arrow_down!!
            }
            _keyboard_arrow_down =
                ImageVector.Builder(
                    name = "keyboard_arrow_down",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(12f, 15.4f)
                            lineTo(6f, 9.4f)
                            lineTo(7.4f, 8f)
                            lineTo(12f, 12.6f)
                            lineTo(16.6f, 8f)
                            lineTo(18f, 9.4f)
                            lineToRelative(-6f, 6f)
                            close()
                        }
                    }
                    .build()
            return _keyboard_arrow_down!!
        }

    private var _keyboard_arrow_down: ImageVector? = null

    @Suppress("CheckReturnValue")
    public val keyboard_arrow_right: ImageVector
        get() {
            if (_keyboard_arrow_right != null) {
                return _keyboard_arrow_right!!
            }
            _keyboard_arrow_right =
                ImageVector.Builder(
                    name = "keyboard_arrow_right",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(12.6f, 12f)
                            lineTo(8f, 7.4f)
                            lineTo(9.4f, 6f)
                            lineToRelative(6f, 6f)
                            lineToRelative(-6f, 6f)
                            lineTo(8f, 16.6f)
                            lineTo(12.6f, 12f)
                            close()
                        }
                    }
                    .build()
            return _keyboard_arrow_right!!
        }

    private var _keyboard_arrow_right: ImageVector? = null

    @Suppress("CheckReturnValue")
    public val close: ImageVector
        get() {
            if (_close != null) {
                return _close!!
            }
            _close =
                ImageVector.Builder(
                    name = "close",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(12f, 13.4f)
                            lineTo(7.1f, 18.3f)
                            quadTo(6.83f, 18.58f, 6.4f, 18.58f)
                            reflectiveQuadTo(5.7f, 18.3f)
                            quadTo(5.43f, 18.02f, 5.43f, 17.6f)
                            reflectiveQuadTo(5.7f, 16.9f)
                            lineTo(10.6f, 12f)
                            lineTo(5.7f, 7.1f)
                            quadTo(5.43f, 6.82f, 5.43f, 6.4f)
                            reflectiveQuadTo(5.7f, 5.7f)
                            reflectiveQuadTo(6.4f, 5.43f)
                            reflectiveQuadTo(7.1f, 5.7f)
                            lineTo(12f, 10.6f)
                            lineTo(16.9f, 5.7f)
                            quadTo(17.18f, 5.43f, 17.6f, 5.43f)
                            reflectiveQuadTo(18.3f, 5.7f)
                            reflectiveQuadToRelative(0.27f, 0.7f)
                            reflectiveQuadTo(18.3f, 7.1f)
                            lineTo(13.4f, 12f)
                            lineToRelative(4.9f, 4.9f)
                            quadToRelative(0.27f, 0.28f, 0.27f, 0.7f)
                            quadToRelative(0f, 0.42f, -0.27f, 0.7f)
                            reflectiveQuadToRelative(-0.7f, 0.27f)
                            reflectiveQuadTo(16.9f, 18.3f)
                            lineTo(12f, 13.4f)
                            close()
                        }
                    }
                    .build()
            return _close!!
        }

    private var _close: ImageVector? = null
}