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
package tech.feldman.betterrecords.client.handler

import tech.feldman.betterrecords.ID
import tech.feldman.betterrecords.api.event.RadioInsertEvent
import tech.feldman.betterrecords.api.event.RecordInsertEvent
import tech.feldman.betterrecords.api.event.SoundStopEvent
import tech.feldman.betterrecords.client.sound.SoundManager
import tech.feldman.betterrecords.extensions.distanceTo
import net.minecraft.client.Minecraft
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.network.NetworkRegistry
import net.minecraftforge.fml.relauncher.Side
import tech.feldman.betterrecords.api.event.LaserHeightChangeEvent
import tech.feldman.betterrecords.block.tile.TileLaser

@Mod.EventBusSubscriber(modid = ID, value = [Side.CLIENT])
object LaserEventHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onLaserHeightChangeEvent(event: LaserHeightChangeEvent) {
        val (pos, dimension, laserHeight) = event
        val world = Minecraft.getMinecraft().world


        (world.getTileEntity(pos) as? TileLaser)?.let { te ->
            te.length = laserHeight;
        }
    }
}
