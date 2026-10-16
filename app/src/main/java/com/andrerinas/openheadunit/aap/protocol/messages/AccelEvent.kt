package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class AccelEvent(x: Float, y: Float, z: Float)
    : SensorEvent(Sensors.SensorType.ACCEL_VALUE, makeProto(x, y, z)) {

    companion object {
        private fun makeProto(x: Float, y: Float, z: Float): Message {
            return Sensors.SensorBatch.newBuilder().also {
                it.addAccelData(Sensors.SensorBatch.AccelerometerData.newBuilder().apply {
                    accelerationXE3 = (x * 1000).toInt()
                    accelerationYE3 = (y * 1000).toInt()
                    accelerationZE3 = (z * 1000).toInt()
                }.build())
            }.build()
        }
    }
}
