package com.sbwnpc.squad.network

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

/*
 * The packet vocabulary of Payloads.kt on Minecraft 1.20.1, which has no payload, type or codec
 * classes: a packet writes and reads itself on a [FriendlyByteBuf], and Forge's channel carries it.
 * NeoForge and Fabric on 1.21 supply vanilla-backed versions of these names.
 */

/** A packet either side can send. */
interface PacketPayload {
    fun type(): PayloadType<*>
}

/** A packet's identity: the id it is registered under. */
class PayloadType<T : PacketPayload>(val id: ResourceLocation)

/** How a packet [T] is written to and read from a buffer. */
class PayloadCodec<T>(val encode: (FriendlyByteBuf, T) -> Unit, val decode: (FriendlyByteBuf) -> T)

/** The field codecs and combinators packets are written with. */
object Codecs {
    val VAR_INT = PayloadCodec<Int>({ buf, v -> buf.writeVarInt(v) }, { it.readVarInt() })
    val BOOL = PayloadCodec<Boolean>({ buf, v -> buf.writeBoolean(v) }, { it.readBoolean() })
    val STRING = PayloadCodec<String>({ buf, v -> buf.writeUtf(v) }, { it.readUtf() })
    val COMPOUND_TAG = PayloadCodec<CompoundTag>({ buf, v -> buf.writeNbt(v) }, { requireNotNull(it.readNbt()) { "Missing compound tag" } })
    val BLOCK_POS = PayloadCodec<BlockPos>({ buf, v -> buf.writeBlockPos(v) }, { it.readBlockPos() })

    fun <T> unit(instance: T): PayloadCodec<T> = PayloadCodec({ _, _ -> }, { instance })

    fun <T> of(encoder: (FriendlyByteBuf, T) -> Unit, decoder: (FriendlyByteBuf) -> T): PayloadCodec<T> = PayloadCodec(encoder, decoder)

    fun <A, T> composite(a: PayloadCodec<A>, getA: (T) -> A, build: (A) -> T): PayloadCodec<T> =
        PayloadCodec({ buf, v -> a.encode(buf, getA(v)) }, { buf -> build(a.decode(buf)) })

    fun <A, B, T> composite(a: PayloadCodec<A>, getA: (T) -> A, b: PayloadCodec<B>, getB: (T) -> B, build: (A, B) -> T): PayloadCodec<T> =
        PayloadCodec(
            { buf, v ->
                a.encode(buf, getA(v))
                b.encode(buf, getB(v))
            },
            { buf -> build(a.decode(buf), b.decode(buf)) },
        )

    fun <A, B, C, T> composite(
        a: PayloadCodec<A>,
        getA: (T) -> A,
        b: PayloadCodec<B>,
        getB: (T) -> B,
        c: PayloadCodec<C>,
        getC: (T) -> C,
        build: (A, B, C) -> T,
    ): PayloadCodec<T> = PayloadCodec(
        { buf, v ->
            a.encode(buf, getA(v))
            b.encode(buf, getB(v))
            c.encode(buf, getC(v))
        },
        { buf -> build(a.decode(buf), b.decode(buf), c.decode(buf)) },
    )

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
    ): PayloadCodec<T> = PayloadCodec(
        { buf, v ->
            a.encode(buf, getA(v))
            b.encode(buf, getB(v))
            c.encode(buf, getC(v))
            d.encode(buf, getD(v))
        },
        { buf -> build(a.decode(buf), b.decode(buf), c.decode(buf), d.decode(buf)) },
    )
}
