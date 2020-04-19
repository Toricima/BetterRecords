/**
 * The MIT License
 *
 * Copyright (c) 2019 Nicholas Feldman
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package tech.feldman.betterrecords.client.sound

import net.minecraft.client.Minecraft
import net.minecraft.client.audio.SoundManager
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.client.event.sound.SoundLoadEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.relauncher.ReflectionHelper
import net.minecraftforge.fml.relauncher.Side
import org.apache.commons.io.FilenameUtils
import org.lwjgl.BufferUtils
import org.lwjgl.openal.AL10
import paulscode.sound.SoundSystem
import tech.feldman.betterrecords.ID
import tech.feldman.betterrecords.ModConfig
import tech.feldman.betterrecords.api.ISoundSource
import tech.feldman.betterrecords.api.record.IRecordAmplitude
import tech.feldman.betterrecords.api.sound.Sound
import tech.feldman.betterrecords.api.wire.IRecordWireHome
import tech.feldman.betterrecords.block.tile.ModTile
import tech.feldman.betterrecords.block.tile.TileRadio
import tech.feldman.betterrecords.client.handler.ClientRenderHandler
import tech.feldman.betterrecords.util.downloadFile
import tech.feldman.betterrecords.util.getGainForPlayerPosition
import tech.feldman.betterrecords.util.getIngameVolume
import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.*
import javax.sound.sampled.*
import kotlin.collections.HashMap
import kotlin.math.absoluteValue

@Mod.EventBusSubscriber(modid = ID, value = [Side.CLIENT])
object SoundPlayer {

    private val downloadFolder = File(Minecraft.getMinecraft().mcDataDir, "betterrecords/cache")

    private val playingSounds = HashMap<Pair<BlockPos, Int>, Sound>()

    fun playSound(pos: BlockPos, dimension: Int, sound: Sound) {
        tech.feldman.betterrecords.BetterRecords.logger.info("Playing sound at $pos in Dimension $dimension")

        ClientRenderHandler.nowDownloading = sound.name
        ClientRenderHandler.showDownloading = true

        val targetFile = File(downloadFolder, FilenameUtils.getName(sound.url).replace(Regex("[^a-zA-Z0-9_\\.]"), "_"))

        downloadFile(URL(sound.url), targetFile,
                update = { curr, total ->
                    ClientRenderHandler.downloadPercent = curr / total
                },
                success = {
                    ClientRenderHandler.showDownloading = false
                    playingSounds[Pair(pos, dimension)] = sound
                    ClientRenderHandler.showPlayingWithTimeout(sound.name)
                    playFile(targetFile, pos, dimension)
                },
                failure = {
                    ClientRenderHandler.showDownloading = false
                }
        )
    }

    fun playSoundFromStream(pos: BlockPos, dimension: Int, sound: Sound) {
        val url = URL(if (sound.url.startsWith("http")) sound.url else "http://${sound.url}")
        tech.feldman.betterrecords.BetterRecords.logger.info("Playing sound from stream at $pos in $dimension from $url")

        val urlConn = IcyURLConnection(url).apply {
            instanceFollowRedirects = true
        }

        urlConn.connect()

        playingSounds[Pair(pos, dimension)] = sound

        ClientRenderHandler.showPlayingWithTimeout(sound.name)
        playStream(urlConn.inputStream, pos, dimension)
    }

    fun isSoundPlayingAt(pos: BlockPos, dimension: Int) =
            playingSounds.containsKey(Pair(pos, dimension))

    fun getSoundPlayingAt(pos: BlockPos, dimension: Int) =
            playingSounds[Pair(pos, dimension)]

    fun stopPlayingAt(pos: BlockPos, dimension: Int) {
        tech.feldman.betterrecords.BetterRecords.logger.info("Stopping sound at $pos in Dimension $dimension")
        playingSounds.remove(Pair(pos, dimension))
    }

    private fun playFile(file: File, pos: BlockPos, dimension: Int) {
        play(AudioSystem.getAudioInputStream(file), pos, dimension)
    }

    private fun playStream(stream: InputStream, pos: BlockPos, dimension: Int) {
        play(AudioSystem.getAudioInputStream(stream), pos, dimension)
    }

    private fun play(ain: AudioInputStream, pos: BlockPos, dimension: Int) {
        val baseFormat = ain.format
        val decodedFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.sampleRate, 16,
                baseFormat.channels,
                baseFormat.channels * 2,
                baseFormat.sampleRate,
                false
        )

        val din = AudioSystem.getAudioInputStream(decodedFormat, ain)
        rawPlay(decodedFormat, din, pos, dimension)
        ain.close()
    }

    private fun checkALError(name: String) {
        val err = AL10.alGetError()
        if (err != 0) {
            val errName = when (err) {
                AL10.AL_INVALID_NAME -> "Invalid Name"
                AL10.AL_INVALID_OPERATION -> "Invalid Operation"
                AL10.AL_OUT_OF_MEMORY -> "Out of Memory"
                AL10.AL_INVALID_VALUE -> "Invalid Value"
                else -> "???"
            }
            throw Exception("Found error ${err.toString(16)} $errName ($name)")
        }
    }

    class Speaker(val pos: Vec3d, val te: ISoundSource) {
        var source = 0

        // Don't queue too much data, otherwise it'll keep on playing after exiting the game/world
        val queueMaxSize = 8
        var knownBuffers: IntBuffer = BufferUtils.createIntBuffer(queueMaxSize)
        val unusedBuffers = ArrayDeque<Int>(queueMaxSize) as Queue<Int>


        fun start() {
            source = AL10.alGenSources()
            checkALError("source gen")

            // Sound needs to come from within the speaker, not on the the corner/floor
            AL10.alSource3f(source,
                    AL10.AL_POSITION,
                    pos.x.toFloat(),
                    pos.y.toFloat(),
                    pos.z.toFloat()
            )
            checkALError("position")


            AL10.alSourcef(source, AL10.AL_PITCH, 1.0F)
            checkALError("Changing Pitch")

            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE)
            checkALError("Changing looping")

            // Set reference distance to _inside_ the block
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0.05f)
            checkALError("Changing ref distance")

            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.4f)
            checkALError("Changing rolloff factor")

            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 50f)
            checkALError("Changing max distance")

            val rotation = te.getRotationDegrees()
            // Rotation of TileRadio is NESW, not degrees. 1.0 = north
            val direction = Vec3d.fromPitchYaw(0f, rotation)

            AL10.alSource3f(source, AL10.AL_DIRECTION, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
            checkALError("Changing direction")

            // Half-cone angles, so 90 for 180 coverage
            AL10.alSourcef(source, AL10.AL_CONE_OUTER_ANGLE, 90.0f)
            checkALError("Changing outer cone angle")
            AL10.alSourcef(source, AL10.AL_CONE_INNER_ANGLE, 60.0f)
            checkALError("Changing inner cone angle")


            AL10.alSourcef(source, AL10.AL_CONE_OUTER_GAIN, 0.2f)
            checkALError("Changing outer cone gain")


            // Prepare buffers
            AL10.alGenBuffers(knownBuffers)
            checkALError("Knownbuffer")

            (0 until queueMaxSize).forEach { x ->
                //println("Adding entry ${knownBuffers[x]}")
                unusedBuffers.add(knownBuffers[x])
            }
        }

        fun stop() {
            AL10.alSourceStop(source)
            AL10.alDeleteBuffers(knownBuffers)
            AL10.alDeleteSources(source)
        }

        fun handleProcessedBuffers() {
            if (!AL10.alIsSource(source)) {
                println("Source is not a source?")
            }

            val processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
            checkALError("Buffers processed")
            if (processed > 0) {
                val ib = BufferUtils.createIntBuffer(processed)

                AL10.alSourceUnqueueBuffers(source, ib)
                checkALError("Unqueue buffers")
                (0 until processed).forEach { x ->
                    //println("Writing back buffer ${ib[x]}")
                    unusedBuffers.add(ib[x])
                }
            }
        }

        fun setVolume(volume: Float) {
            // AL_GAIN defines a scalar amplitude multiplier. As a source attribute, it applies to that
            // particular source only. As a listener attribute, it effectively applies to all sources in the
            // current context. The default 1.0 means that the sound is unattenuated. An AL_GAIN
            // value of 0.5 is equivalent to an attenuation of 6 dB
            AL10.alSourcef(source, AL10.AL_GAIN, 3 * volume)
            checkALError("Changing gain")
        }

        fun hasFreeBuffers() = !unusedBuffers.isEmpty()

        var started = false

        // b should be the buffer with the channel already selected/filtered
        fun streamData(b: ByteBuffer, sampleRate: Int, bufferFormat: Int) {
            val usableBuffer = unusedBuffers.poll()

            b.rewind()

            //println("Writing chunk to buffer ${usableBuffer.toInt()}, ${AL10.alIsBuffer(usableBuffer.toInt())} $sampleRate $bufferFormat.")
            AL10.alBufferData(
                    usableBuffer.toInt(),
                    bufferFormat,
                    b,
                    sampleRate
            )
            checkALError("Setting albufferdata")

            AL10.alSourceQueueBuffers(source, usableBuffer.toInt())
            checkALError("Queued")

            if (!started) {
                started = true

                AL10.alSourcePlay(source)
                checkALError("Source play")
            }
        }
    }

    class StereoSpeaker(val pos: Vec3d, val te: ISoundSource) {
        val leftSpeaker = Speaker(pos.addVector(-0.01, 0.0, 0.0), te)
        val rightSpeaker = Speaker(pos.addVector(0.01, 0.0, 0.0), te)

        fun getChannelData(b: ByteArray, bLen: Int): Pair<ByteBuffer, ByteBuffer> {
            val leftChannel = ByteBuffer.allocateDirect(bLen / 2)
            val rightChannel = ByteBuffer.allocateDirect(bLen / 2)
            (0 until bLen step 4).forEach {
                leftChannel.put(b[it + 0])
                leftChannel.put(b[it + 1])

                rightChannel.put(b[it + 2])
                rightChannel.put(b[it + 3])
            }

            // Mark len and reset
            leftChannel.flip()
            rightChannel.flip()

            return Pair(leftChannel, rightChannel)
        }

        fun streamData(b: ByteArray, bLen: Int, sampleRate: Int, bufferFormat: Int) {
            val (l, r) = getChannelData(b, bLen)
            leftSpeaker.streamData(l, sampleRate, bufferFormat)
            rightSpeaker.streamData(r, sampleRate, bufferFormat)
        }

        fun setVolume(volume: Float) {
            leftSpeaker.setVolume(volume)
            rightSpeaker.setVolume(volume)
        }

        fun stop() {
            leftSpeaker.stop()
            rightSpeaker.stop()
        }

        fun handleProcessedBuffers() {
            leftSpeaker.handleProcessedBuffers()
            rightSpeaker.handleProcessedBuffers()
        }

        fun start() {
            leftSpeaker.start()
            rightSpeaker.start()
        }

        fun hasFreeBuffers() = leftSpeaker.hasFreeBuffers() && rightSpeaker.hasFreeBuffers()
    }

    private fun rawPlay(targetFormat: AudioFormat, din: AudioInputStream, pos: BlockPos, dimension: Int) {
        // pspeed42 from jMonkeyEngine:
        // "For positional audio the sound has to be mono and you have to update the position if the listener..."
        // So convert the incoming Stereo audio to 2x mono streams

        // https://github.com/kovertopz/Paulscode-SoundSystem/
        // https://stackoverflow.com/a/5518320
        // https://github.com/kcat/openal-soft/wiki/Programmer%27s-Guide#queuing-buffers-on-a-source
        // https://www.codota.com/code/java/methods/com.jme3.audio.openal.AL/alSourcef


        if (Minecraft.getMinecraft().world?.provider?.dimension != dimension) return
        val te = Minecraft.getMinecraft().world.getTileEntity(pos)!! as ISoundSource

        val allSpeakers = mutableListOf<StereoSpeaker>()

        val allSpeakerPositions = mutableSetOf<BlockPos>()
        allSpeakerPositions.add(pos)

        allSpeakerPositions.addAll(te.connections.map { BlockPos(it.x2, it.y2, it.z2) })

        allSpeakerPositions.forEach {
            val te = Minecraft.getMinecraft().world.getTileEntity(it)
            if (te is ISoundSource) {
                allSpeakers.add(StereoSpeaker(
                        Vec3d(it.x.toDouble() + 0.5, it.y.toDouble() + 0.5, it.z.toDouble() + 0.5),
                        te
                ))
            }
        }

        allSpeakers.forEach { it.start() }

        val buffer = ByteArray((targetFormat.sampleRate * targetFormat.frameSize).toInt())

        val bufferFormat = AL10.AL_FORMAT_MONO16

        while (isSoundPlayingAt(pos, dimension)) {
            allSpeakers.forEach { it.handleProcessedBuffers() }

            val currentVolume = getIngameVolume()

            allSpeakers.forEach { it.setVolume(currentVolume) }

            while (allSpeakers.all { it.hasFreeBuffers() }) {
                val bytes = din.read(buffer)
                if (bytes <= 0) break
                allSpeakers.forEach { it.streamData(buffer, bytes, targetFormat.sampleRate.toInt(), bufferFormat) }

                updateLights(buffer, pos, dimension)
            }
        }

        stopPlayingAt(pos, dimension)
        allSpeakers.forEach { it.stop() }
    }

    private fun updateLights(buffer: ByteArray, pos: BlockPos, dimension: Int) {
        if (Minecraft.getMinecraft().world?.provider?.dimension != dimension) {
            return
        }

        var unscaledTreble = -1F
        var unscaledBass = -1F

        val te = Minecraft.getMinecraft().world.getTileEntity(pos)

        (te as? IRecordWireHome)?.let {
            te.addTreble(getUnscaledWaveform(buffer, true, false))
            te.addBass(getUnscaledWaveform(buffer, false, false))

            for (connection in te.connections) {
                val connectedTe = Minecraft.getMinecraft().world.getTileEntity(BlockPos(connection.x2, connection.y2, connection.z2))

                (connectedTe as? IRecordAmplitude)?.let {
                    if (unscaledTreble == -1F || unscaledBass == 11F) {
                        unscaledTreble = getUnscaledWaveform(buffer, true, true)
                        unscaledBass = getUnscaledWaveform(buffer, false, true)
                    }

                    connectedTe.treble = unscaledTreble
                    connectedTe.bass = unscaledBass
                }
            }
        }
    }

    private fun getUnscaledWaveform(buffer: ByteArray, high: Boolean, control: Boolean): Float {
        val toReturn = ByteArray(buffer.size / 2)

        var avg = 0.0F

        for ((index, audioByte) in ((if (high) 0 else 1) until (buffer.size) step 2).withIndex()) {
            toReturn[index] = buffer[audioByte]
            avg += toReturn[index]
        }

        avg /= toReturn.size

        if (control) {
            if (avg < 0F) {
                avg = avg.absoluteValue
            }

            if (avg > 20F) {
                return if (ModConfig.client.flashMode < 3) {
                    1F
                } else {
                    2F
                }
            }
        }
        return avg
    }
}
