package com.siroha.flashtool.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android sparse-image splitting, the same trick the PC `fastboot flash` uses when an image is
 * bigger than the device's `max-download-size`: the image is cut into several smaller sparse
 * images, each one is downloaded and `flash`ed in turn, and the bootloader assembles them.
 *
 * Works for both inputs:
 *  - an image that is already in Android sparse format (most system.img / userdata.img of
 *    Xiaomi fastboot ROMs), and
 *  - a plain raw image (treated as one big RAW chunk).
 *
 * Nothing is written to disk: each piece is produced on the fly straight into the USB stream, so
 * no extra free space or cache copy is needed. Only chunk HEADERS are read while planning.
 */
object SparseImage {
    private const val SPARSE_MAGIC = 0xED26FF3A.toInt()
    private const val FILE_HDR = 28
    private const val CHUNK_HDR = 12
    private const val TYPE_RAW = 0xCAC1
    private const val TYPE_FILL = 0xCAC2
    private const val TYPE_DONT_CARE = 0xCAC3
    private const val TYPE_CRC32 = 0xCAC4
    private const val RAW_BLOCK_SIZE = 4096
    private const val COPY_BUFFER = 1024 * 1024

    /** One data-carrying run of blocks in the full image (DONT_CARE runs are just gaps between these). */
    internal data class Op(val type: Int, val startBlock: Long, val blocks: Long, val srcOffset: Long = 0, val fill: Int = 0)

    /** One downloadable sparse image: [size] is its exact byte length. */
    class Piece internal constructor(internal val ops: List<Op>, val size: Long)

    class Plan internal constructor(
        internal val src: File,
        internal val blockSize: Int,
        internal val totalBlocks: Long,
        /** True when the source is a raw image whose last block must be zero-padded. */
        internal val padTail: Boolean,
        val pieces: List<Piece>
    )

    fun isSparse(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(4)
            raf.readFully(b)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int == SPARSE_MAGIC
        }
    }.getOrDefault(false)

    /** Splits [src] into pieces that each fit in [maxBytes] (the device's max-download-size). */
    fun plan(src: File, maxBytes: Long): Plan {
        val sparse = isSparse(src)
        val blockSize: Int
        val totalBlocks: Long
        val srcOps: List<Op>
        if (sparse) {
            val parsed = parseSparse(src)
            blockSize = parsed.blockSize
            totalBlocks = parsed.totalBlocks
            srcOps = parsed.ops
        } else {
            blockSize = RAW_BLOCK_SIZE
            totalBlocks = (src.length() + blockSize - 1) / blockSize
            srcOps = if (totalBlocks > 0) listOf(Op(TYPE_RAW, 0, totalBlocks, 0)) else emptyList()
        }
        require(maxBytes > FILE_HDR + 4L * CHUNK_HDR + blockSize) { "max download size ($maxBytes bytes) is too small to split into" }

        val pieces = mutableListOf<Piece>()
        var cur = mutableListOf<Op>()
        var curBytes = 0L
        var curEnd = 0L

        fun flush() {
            if (cur.isNotEmpty()) pieces.add(finish(cur, totalBlocks, blockSize))
            cur = mutableListOf()
            curBytes = 0
        }

        for (op in srcOps) {
            var start = op.startBlock
            var left = op.blocks
            var srcOff = op.srcOffset
            while (left > 0) {
                // Worst case reserved up front: file header + leading and trailing skip chunk.
                val gap = if (cur.isNotEmpty() && start > curEnd) CHUNK_HDR else 0
                val room = maxBytes - FILE_HDR - 2L * CHUNK_HDR - curBytes - gap - CHUNK_HDR
                if (op.type == TYPE_RAW) {
                    val fit = room / blockSize
                    if (fit <= 0) {
                        check(cur.isNotEmpty()) { "cannot fit a single block into ${maxBytes} bytes" }
                        flush()
                        continue
                    }
                    val take = minOf(left, fit)
                    cur.add(Op(TYPE_RAW, start, take, srcOff))
                    curBytes += gap + CHUNK_HDR + take * blockSize
                    curEnd = start + take
                    start += take
                    srcOff += take * blockSize
                    left -= take
                    if (left > 0) flush()
                } else {
                    if (room < 4) {
                        check(cur.isNotEmpty()) { "cannot fit a fill chunk into ${maxBytes} bytes" }
                        flush()
                        continue
                    }
                    cur.add(Op(TYPE_FILL, start, left, fill = op.fill))
                    curBytes += gap + CHUNK_HDR + 4
                    curEnd = start + left
                    left = 0
                }
            }
        }
        flush()
        return Plan(src, blockSize, totalBlocks, padTail = !sparse, pieces = pieces)
    }

    private fun finish(ops: List<Op>, totalBlocks: Long, blockSize: Int): Piece {
        var size = FILE_HDR.toLong()
        var pos = 0L
        for (op in ops) {
            if (op.startBlock > pos) size += CHUNK_HDR
            size += CHUNK_HDR + if (op.type == TYPE_RAW) op.blocks * blockSize else 4L
            pos = op.startBlock + op.blocks
        }
        if (pos < totalBlocks) size += CHUNK_HDR
        return Piece(ops, size)
    }

    private class Parsed(val blockSize: Int, val totalBlocks: Long, val ops: List<Op>)

    private fun parseSparse(src: File): Parsed = RandomAccessFile(src, "r").use { raf ->
        val h = ByteArray(FILE_HDR)
        raf.readFully(h)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int != SPARSE_MAGIC) throw IOException("not a sparse image")
        val major = bb.short.toInt() and 0xFFFF
        bb.short // minor
        val fileHdr = bb.short.toInt() and 0xFFFF
        val chunkHdr = bb.short.toInt() and 0xFFFF
        val blockSize = bb.int
        val totalBlks = Integer.toUnsignedLong(bb.int)
        val totalChunks = Integer.toUnsignedLong(bb.int)
        if (major != 1 || fileHdr < FILE_HDR || chunkHdr < CHUNK_HDR || blockSize <= 0 || blockSize % 4 != 0) {
            throw IOException("unsupported sparse header")
        }
        val ops = mutableListOf<Op>()
        var pos = 0L
        raf.seek(fileHdr.toLong())
        val ch = ByteArray(CHUNK_HDR)
        for (i in 0 until totalChunks) {
            val chStart = raf.filePointer
            raf.readFully(ch)
            val cb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
            val type = cb.short.toInt() and 0xFFFF
            cb.short // reserved
            val chunkBlocks = Integer.toUnsignedLong(cb.int)
            val totalSz = Integer.toUnsignedLong(cb.int)
            val payload = chStart + chunkHdr
            when (type) {
                TYPE_RAW -> {
                    if (totalSz != chunkHdr + chunkBlocks * blockSize) throw IOException("corrupt RAW chunk")
                    ops += Op(TYPE_RAW, pos, chunkBlocks, payload)
                    pos += chunkBlocks
                }
                TYPE_FILL -> {
                    raf.seek(payload)
                    val f = ByteArray(4)
                    raf.readFully(f)
                    ops += Op(TYPE_FILL, pos, chunkBlocks, fill = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).int)
                    pos += chunkBlocks
                }
                TYPE_DONT_CARE -> pos += chunkBlocks
                TYPE_CRC32 -> Unit
                else -> throw IOException("unknown chunk type 0x${type.toString(16)}")
            }
            raf.seek(chStart + totalSz)
        }
        Parsed(blockSize, maxOf(totalBlks, pos), ops)
    }

    /** Streams [piece] as a complete sparse image into [out] (exactly [Piece.size] bytes). */
    fun writePiece(plan: Plan, piece: Piece, out: OutputStream) {
        val le = ByteOrder.LITTLE_ENDIAN
        var chunks = 0
        var pos = 0L
        for (op in piece.ops) {
            if (op.startBlock > pos) chunks++
            chunks++
            pos = op.startBlock + op.blocks
        }
        if (pos < plan.totalBlocks) chunks++

        val header = ByteBuffer.allocate(FILE_HDR).order(le)
        header.putInt(SPARSE_MAGIC)
        header.putShort(1).putShort(0)
        header.putShort(FILE_HDR.toShort()).putShort(CHUNK_HDR.toShort())
        header.putInt(plan.blockSize)
        header.putInt(plan.totalBlocks.toInt())
        header.putInt(chunks)
        header.putInt(0)
        out.write(header.array())

        fun chunkHeader(type: Int, blocks: Long, totalSz: Long) {
            val b = ByteBuffer.allocate(CHUNK_HDR).order(le)
            b.putShort(type.toShort()).putShort(0)
            b.putInt(blocks.toInt()).putInt(totalSz.toInt())
            out.write(b.array())
        }

        pos = 0L
        RandomAccessFile(plan.src, "r").use { raf ->
            val buf = ByteArray(COPY_BUFFER)
            val fileLen = raf.length()
            for (op in piece.ops) {
                if (op.startBlock > pos) chunkHeader(TYPE_DONT_CARE, op.startBlock - pos, CHUNK_HDR.toLong())
                if (op.type == TYPE_RAW) {
                    var remaining = op.blocks * plan.blockSize
                    chunkHeader(TYPE_RAW, op.blocks, CHUNK_HDR + remaining)
                    var off = op.srcOffset
                    raf.seek(off)
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val avail = if (off < fileLen) minOf(want.toLong(), fileLen - off).toInt() else 0
                        val read = if (avail > 0) raf.read(buf, 0, avail) else 0
                        if (read > 0) {
                            out.write(buf, 0, read)
                            off += read
                            remaining -= read
                        } else if (plan.padTail) {
                            // Raw image whose size isn't a whole number of blocks: pad with zeros.
                            java.util.Arrays.fill(buf, 0, want, 0)
                            out.write(buf, 0, want)
                            remaining -= want
                        } else {
                            throw IOException("sparse image is truncated")
                        }
                    }
                } else {
                    chunkHeader(TYPE_FILL, op.blocks, CHUNK_HDR + 4L)
                    out.write(ByteBuffer.allocate(4).order(le).putInt(op.fill).array())
                }
                pos = op.startBlock + op.blocks
            }
        }
        if (pos < plan.totalBlocks) chunkHeader(TYPE_DONT_CARE, plan.totalBlocks - pos, CHUNK_HDR.toLong())
    }
}
