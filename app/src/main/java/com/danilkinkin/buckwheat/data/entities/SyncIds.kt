package com.danilkinkin.buckwheat.data.entities

import java.util.UUID

fun newSyncId(): String = UUID.randomUUID().toString()
