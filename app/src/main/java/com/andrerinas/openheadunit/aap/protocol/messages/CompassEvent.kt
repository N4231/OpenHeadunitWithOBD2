package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class CompassEvent(bearingDeg: Float, pitchDeg: Float, rollDeg: Float)
    : SensorEvent(Sensors.SensorType.COMPASS_VALUE, makeProto(bearingDeg, pitchDeg, rollDeg)) {

    companion object {
        private fun makeProto(bearingDeg: Float, pitchDeg: Float, rollDeg: Float): Message {
            return Sensors.SensorBatch.newBuilder().also {
                it.addCompassData(Sensors.SensorBatch.CompassData.newBuilder().apply {
                    bearingE6 = (bearingDeg * 1000000).toInt()
                    pitchE6 = (pitchDeg * 1000000).toInt()
                    rollE6 = (rollDeg * 1000000).toInt()
                }.build())
            }.build()
        }
    }
}
