package com.viperdam.kidsprayer.extensions

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet

/**
 * Extension function to run a lambda when an animation ends
 */
fun AnimatorSet.doOnEnd(onEnd: () -> Unit) {
    this.addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            onEnd()
        }
    })
} 