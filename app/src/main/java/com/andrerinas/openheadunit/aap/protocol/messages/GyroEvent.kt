package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class GyroEvent(x: Float, y: Float, z: Float)
    : SensorEvent(Sensors.SensorType.GYRO_VALUE, makeProto(x, y, z)) {

    companion object {
        private fun makeProto(x: Float, y: Float, z: Float): Message {
            return Sensors.SensorBatch.newBuilder().also {
                it.addGyroData(Sensors.SensorBatch.GyroscopeData.newBuilder().apply {
                    rotationSpeedXE3 = (x * 1000).toInt()
                    rotationSpeedYE3 = (y * 1000).toInt()
                    rotationSpeedZE3 = (z * 1000).toInt()
                }.build())
            }.build()
        }
    }
}
