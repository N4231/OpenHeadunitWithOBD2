package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message
import com.google.protobuf.UnknownFieldSet

class SpeedEvent(speedKmh: Int)
    : SensorEvent(Sensors.SensorType.CAR_SPEED_VALUE, makeProto(speedKmh)) {

    companion object {
        private fun makeProto(speedKmh: Int): Message {
            return Sensors.SensorBatch.newBuilder().also {
                it.addSpeedData(Sensors.SensorBatch.SpeedData.newBuilder().apply {
                    // km/h -> mm/s (1000000 / 3600 = 2500 / 9)
                    val speedMmPerSec = (speedKmh * 2500 / 9)
                    // Field 1: speed_e3 (Display speed)
                    speedE3 = speedMmPerSec
                    // Field 3: raw_speed_e3 (Raw ECU speed) - manually set via UnknownFieldSet since Sensors.java is pre-compiled
                    setUnknownFields(
                        UnknownFieldSet.newBuilder()
                        .addField(3, UnknownFieldSet.Field.newBuilder().addVarint(speedMmPerSec.toLong()).build())
                        .build())
                }.build())
            }.build()
        }
    }
}
