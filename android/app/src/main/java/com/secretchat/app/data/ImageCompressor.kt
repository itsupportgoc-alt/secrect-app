package com.secretchat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Turns picked photos into small, upright JPEGs that upload quickly. */
object ImageCompressor {

    private const val MAX_SIDE = 1600
    private const val QUALITY = 82
    private const val AVATAR_SIDE = 512

    /** Chat photo: at most [MAX_SIDE] px on the long side. */
    fun compress(context: Context, uri: Uri): ByteArray {
        var bitmap = decodeUpright(context, uri, MAX_SIDE, fitShortestSide = false)
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        return toJpeg(bitmap)
    }

    /** Profile picture: centre-cropped square, [AVATAR_SIDE] px. */
    fun compressAvatar(context: Context, uri: Uri): ByteArray {
        val bitmap = decodeUpright(context, uri, AVATAR_SIDE, fitShortestSide = true)
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(
            bitmap,
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            side,
            side,
        )
        val scaled = if (side > AVATAR_SIDE) {
            Bitmap.createScaledBitmap(square, AVATAR_SIDE, AVATAR_SIDE, true)
        } else {
            square
        }
        return toJpeg(scaled)
    }

    private fun decodeUpright(
        context: Context,
        uri: Uri,
        targetSide: Int,
        fitShortestSide: Boolean,
    ): Bitmap {
        val resolver = context.contentResolver

        // Reading only the size returns no bitmap (null), so check the stream itself.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: error("Could not open the photo")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This file is not a supported image" }

        // Decode at roughly the size we need (never smaller than the target),
        // which keeps memory low for big camera photos.
        val side = if (fitShortestSide) {
            minOf(bounds.outWidth, bounds.outHeight)
        } else {
            maxOf(bounds.outWidth, bounds.outHeight)
        }
        var sample = 1
        while (side / (sample * 2) >= targetSide) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("This file is not a supported image")

        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees != 0f) {
            val matrix = Matrix().apply { postRotate(degrees) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun toJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
}
