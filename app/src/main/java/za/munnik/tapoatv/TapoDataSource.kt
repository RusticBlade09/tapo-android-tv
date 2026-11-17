package za.munnik.tapoatv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Lets ExoPlayer read a camera ("tapo://<ip>/hd", "tapo://<ip>/vga") or a hub recording ([clipUri]). */
@OptIn(UnstableApi::class)
class TapoDataSource(private val creds: Creds) : DataSource {
    private var stream: TapoStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val u = dataSpec.uri
        val clip = if (u.path != "/clip") null else
            Clip(u.getQueryParameter("dev")!!, u.getQueryParameter("mac")!!, u.getQueryParameter("s")!!.toLong(), u.getQueryParameter("e")!!.toLong())
        stream = TapoStream(u.host!!, creds, u.path == "/hd", clip).also { it.connect() }
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream!!.read(buffer, offset, length)
    override fun getUri(): Uri? = uri
    override fun close() { stream?.close(); stream = null }
    override fun addTransferListener(transferListener: TransferListener) {}

    companion object {
        fun clipUri(hub: String, c: Clip): Uri = Uri.Builder().scheme("tapo").authority(hub).path("/clip")
            .appendQueryParameter("dev", c.devId).appendQueryParameter("mac", c.mac)
            .appendQueryParameter("s", c.start.toString()).appendQueryParameter("e", c.end.toString()).build()
    }
}
