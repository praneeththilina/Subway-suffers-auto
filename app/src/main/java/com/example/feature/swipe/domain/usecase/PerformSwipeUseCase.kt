package com.example.feature.swipe.domain.usecase

import com.example.feature.swipe.data.repository.SwipeTelemetryRepository
import com.example.service.SwipeControllerService

class PerformSwipeUseCase(
    private val repository: SwipeTelemetryRepository = SwipeTelemetryRepository
) {
    operator fun invoke(direction: String, source: String = "Controller UI"): Boolean {
        return SwipeControllerService.performSwipeWithSource(direction, source)
    }

    fun toggleFloatingMilestoneOverlay(): Boolean {
        return SwipeControllerService.toggleFloatingMilestoneButton()
    }

    fun setAutoJumpActive(active: Boolean) {
        SwipeControllerService.setAutoJumpLoopActive(active)
    }
}
