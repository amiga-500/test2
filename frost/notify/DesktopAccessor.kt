package frost.notify

import dorkbox.tweenEngine.TweenAccessor

internal class DesktopAccessor : TweenAccessor<DesktopNotify> {
    companion object {
        const val X_POS = 0
        const val Y_POS = 1
        const val X_Y_POS = 2
        const val SHAKE = 3
        const val PROGRESS = 4
        const val ANIMATION_OFFSET = 5
    }

    override fun getValues(target: DesktopNotify, tweenType: Int, returnValues: FloatArray): Int {
        when (tweenType) {
            X_POS -> {
                returnValues[0] = target.x.toFloat()
                return 1
            }

            Y_POS -> {
                returnValues[0] = target.y.toFloat()
                return 1
            }

            X_Y_POS -> {
                returnValues[0] = target.x.toFloat()
                returnValues[1] = target.y.toFloat()
                return 2
            }

            SHAKE -> {
                returnValues[0] = target.shakeX.toFloat()
                returnValues[1] = target.shakeY.toFloat()
                return 2
            }

            PROGRESS -> {
                returnValues[0] = target.progress.toFloat()
                return 1
            }

            ANIMATION_OFFSET -> {
                returnValues[0] =
                    target.animationOffsetX.toFloat()

                returnValues[1] =
                    target.animationOffsetY.toFloat()

                return 2
            }
        }
        return 1
    }

    override fun setValues(target: DesktopNotify, tweenType: Int, newValues: FloatArray) {
        when (tweenType) {
            X_POS -> {
                target.setLocationInternal(newValues[0].toInt(), target.y)
                return
            }

            Y_POS -> {
                target.setLocationInternal(target.x, newValues[0].toInt())
                return
            }

            X_Y_POS -> {
                target.setLocationInternal(newValues[0].toInt(), newValues[1].toInt())
                return
            }

            SHAKE -> {
                target.setLocationShake(
                    newValues[0].toInt(),
                    newValues[1].toInt()
                )
                return
            }

            PROGRESS -> {
                target.progress = newValues[0].toInt()
                return
            }

            ANIMATION_OFFSET -> {

                target.setAnimationOffset(
                    newValues[0].toInt(),
                    newValues[1].toInt()
                )

                return
            }
        }
    }
}
