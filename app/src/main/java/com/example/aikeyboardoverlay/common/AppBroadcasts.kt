package com.example.aikeyboardoverlay.common

object AppBroadcasts {
    const val ACTION_FLOATING_BUTTON_CLICKED =
        "com.example.aikeyboardoverlay.ACTION_FLOATING_BUTTON_CLICKED"
    const val ACTION_PROCESSING_STATE_CHANGED =
        "com.example.aikeyboardoverlay.ACTION_PROCESSING_STATE_CHANGED"
    const val EXTRA_STATE = "extra_state"
    const val ACTION_MODEL_STATE_CHANGED =
        "com.example.aikeyboardoverlay.ACTION_MODEL_STATE_CHANGED"
    const val EXTRA_MODEL_READY = "extra_model_ready"
    const val ACTION_MODEL_STATE_QUERY =
        "com.example.aikeyboardoverlay.ACTION_MODEL_STATE_QUERY"
    const val ACTION_MODEL_TEST_REQUEST =
        "com.example.aikeyboardoverlay.ACTION_MODEL_TEST_REQUEST"
    const val EXTRA_PROMPT = "extra_prompt"
    const val ACTION_MODEL_TEST_RESULT =
        "com.example.aikeyboardoverlay.ACTION_MODEL_TEST_RESULT"
    const val EXTRA_OUTPUT = "extra_output"

    const val STATE_IDLE = 0
    const val STATE_PROCESSING = 1
    const val STATE_ERROR = 2
}
