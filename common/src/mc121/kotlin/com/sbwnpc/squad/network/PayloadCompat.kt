package com.sbwnpc.squad.network

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/*
 * The packet vocabulary of [Payloads.kt] on Minecraft 1.21: the payload, type and codec classes are
 * vanilla's. Forge on 1.20.1 has no such classes and supplies its own versions of these names.
 */

/** A packet either side can send. */
interface PacketPayload : CustomPacketPayload

typealias PayloadType<T> = CustomPacketPayload.Type<T>

typealias PayloadCodec<T> = StreamCodec<RegistryFriendlyByteBuf, T>

/** The field codecs and combinators packets are written with. */
object Codecs {
    @Suppress("UNCHECKED_CAST")
    private fun <T> widen(codec: StreamCodec<*, T>): PayloadCodec<T> = codec as PayloadCodec<T>

    val VAR_INT: PayloadCodec<Int> = widen(ByteBufCodecs.VAR_INT)
    val BOOL: PayloadCodec<Boolean> = widen(ByteBufCodecs.BOOL)
    val STRING: PayloadCodec<String> = widen(ByteBufCodecs.STRING_UTF8)
    val COMPOUND_TAG: PayloadCodec<CompoundTag> = widen(ByteBufCodecs.COMPOUND_TAG)
    val BLOCK_POS: PayloadCodec<BlockPos> = widen(BlockPos.STREAM_CODEC)

    fun <T> unit(instance: T): PayloadCodec<T> = StreamCodec.unit(instance)

    fun <T> of(encoder: (RegistryFriendlyByteBuf, T) -> Unit, decoder: (RegistryFriendlyByteBuf) -> T): PayloadCodec<T> =
        StreamCodec.of({ buf, value -> encoder(buf, value) }, { buf -> decoder(buf) })

    fun <A, T> composite(a: PayloadCodec<A>, getA: (T) -> A, build: (A) -> T): PayloadCodec<T> =
        StreamCodec.composite(a, getA, build)

    fun <A, B, T> composite(a: PayloadCodec<A>, getA: (T) -> A, b: PayloadCodec<B>, getB: (T) -> B, build: (A, B) -> T): PayloadCodec<T> =
        StreamCodec.composite(a, getA, b, getB, build)

    fun <A, B, C, T> composite(
        a: PayloadCodec<A>,
        getA: (T) -> A,
        b: PayloadCodec<B>,
        getB: (T) -> B,
        c: PayloadCodec<C>,
        getC: (T) -> C,
        build: (A, B, C) -> T,
    ): PayloadCodec<T> = StreamCodec.composite(a, getA, b, getB, c, getC, build)

    @Suppress("LongParameterList")
    fun <A, B, C, D, T> composite(
        a: PayloadCodec<A>,
        getA: (T) -> A,
        b: PayloadCodec<B>,
        getB: (T) -> B,
        c: PayloadCodec<C>,
        getC: (T) -> C,
        d: PayloadCodec<D>,
        getD: (T) -> D,
        build: (A, B, C, D) -> T,
    ): PayloadCodec<T> = StreamCodec.composite(a, getA, b, getB, c, getC, d, getD, build)
}
