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
                    // 車両から直接取得した値なので、Disp (speed_e3) は0にし、Raw (raw_speed_e3) のみで送信する
                    speedE3 = 0
                    setUnknownFields(
                        UnknownFieldSet.newBuilder()
                        .addField(3, UnknownFieldSet.Field.newBuilder().addVarint(speedMmPerSec.toLong()).build())
                        .build())
                }.build())
            }.build()
        }
    }
}
