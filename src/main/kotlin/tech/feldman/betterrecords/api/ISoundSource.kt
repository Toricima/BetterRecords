package tech.feldman.betterrecords.api

import tech.feldman.betterrecords.api.connection.RecordConnection

interface ISoundSource {
    fun getRotationDegrees(): Float
    val connections: MutableList<RecordConnection>
}