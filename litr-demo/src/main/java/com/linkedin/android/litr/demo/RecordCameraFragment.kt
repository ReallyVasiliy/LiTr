package com.linkedin.android.litr.demo

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.camera2.*
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.*
import android.util.Log
import android.view.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.linkedin.android.litr.codec.MediaCodecEncoder
import com.linkedin.android.litr.demo.audio.AudioRecordConfig
import com.linkedin.android.litr.demo.audio.AudioTrackReader
import com.linkedin.android.litr.demo.camera.CameraHandler
import com.linkedin.android.litr.demo.camera.MultiTargetCameraThread
import com.linkedin.android.litr.demo.camera.CameraThreadListener
import com.linkedin.android.litr.demo.data.AudioTrackFormat
import com.linkedin.android.litr.demo.data.TargetMedia
import com.linkedin.android.litr.demo.databinding.FragmentRecordCameraBinding
import com.linkedin.android.litr.io.*
import com.linkedin.android.litr.recorder.MediaRecordParameters
import com.linkedin.android.litr.recorder.MediaRecordRequestManager
import com.linkedin.android.litr.recorder.readers.ByteBufferTrackReader
import com.linkedin.android.litr.recorder.readers.SurfaceTrackReader
import com.linkedin.android.litr.render.GlVideoRenderer
import com.linkedin.android.litr.render.PassthroughSoftwareRenderer
import com.linkedin.android.litr.render.VideoRenderInputSurface
import com.linkedin.android.litr.utils.TransformationUtil
import java.io.File
import java.util.*

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class RecordCameraFragment : BaseTransformationFragment() {

    private lateinit var surfaceTrackReader: SurfaceTrackReader
    private lateinit var audioTrackReader: ByteBufferTrackReader
    private lateinit var audioConfig: AudioRecordConfig

    private var cameraPreviewSize: Point? = null
    private var cameraRecordSurfaceTexture: VideoRenderInputSurface? = null
    private var previewSurface: Surface? = null
    private var previewSurfaceSize: Point? = null
    private var requestId: String = UUID.randomUUID().toString()
    private var isRecording = false

    private lateinit var binding: FragmentRecordCameraBinding
    private var cameraHandler: CameraHandler? = null
    private lateinit var mediaRecorder: MediaRecordRequestManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaRecorder = MediaRecordRequestManager(context!!.applicationContext)

        audioConfig = AudioRecordConfig.createDefault(1)

        audioTrackReader = AudioTrackReader(audioConfig)

        surfaceTrackReader = object : SurfaceTrackReader {
            override fun drawFrame(surface: Surface, presentationTimeNs: Long) {
                // Do nothing: camera is the frame producer
            }

            override fun start() {}

            override fun stop() {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTranscoding()

        cameraHandler?.stopPreview()
        cameraHandler = null

        // Clear any references to view or data obtained from views
        previewSurface = null
        previewSurfaceSize = null

        cameraRecordSurfaceTexture?.release()
        cameraRecordSurfaceTexture = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?, savedInstanceState: Bundle?
    ): View {

        binding = FragmentRecordCameraBinding.inflate(inflater, container, false)

        binding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                previewSurface = null
                previewSurfaceSize = null
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {

            }
            override fun surfaceCreated(holder: SurfaceHolder) {
                previewSurface = holder.surface
                previewSurfaceSize = Point(holder.surfaceFrame.width(), holder.surfaceFrame.height())
                tryOpenCamera()
            }
        })

        binding.cameraCaptureButton.setOnClickListener {
            if (!isRecording) {
                activity?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_LOCKED
                binding.cameraCaptureButton.setText(R.string.stop)
                isRecording = true
                startTranscoding()
            } else {
                // Stop recording
                activity?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                binding.cameraCaptureButton.setText(R.string.record)
                isRecording = false
                stopTranscoding()
            }
        }

        return binding.root
    }

    private fun stopTranscoding() {
        mediaRecorder.stop(requestId)
    }

    private fun startTranscoding() {
        val previewSurfaceSize = cameraPreviewSize ?: run {
            Log.e(TAG, "Camera preview size is not yet known. It is needed to correctly set the target media format")
            return
        }
        val videoDimensions = Point(previewSurfaceSize.y, previewSurfaceSize.x)

        val targetFile = File(
            TransformationUtil.getTargetFileDirectory(requireContext().applicationContext),
            "recorded_camera_${System.currentTimeMillis()}.mp4"
        )

        val targetMedia = TargetMedia().apply {
            setTargetFile(targetFile)
        }

        val mediaTarget: MediaTarget = MediaMuxerMediaTarget(
            targetMedia.targetFile.path,
            2,
            0,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        val targetFormat = MediaFormat.createVideoFormat("video/avc", videoDimensions.x, videoDimensions.y).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, calculateVideoBitrate(videoDimensions.x, videoDimensions.y))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
        }

        val videoTrackParams = MediaRecordParameters(
            reader = surfaceTrackReader,
            targetTrack = 0,
            targetFormat = targetFormat,
            mediaTarget = mediaTarget,
            encoder = MediaCodecEncoder(),
            // The renderer will encode frames from the camera by sharing this surface with the camera
            renderer = GlVideoRenderer.Builder().setInputSurface(cameraRecordSurfaceTexture).build()
        )

        val audioEncoder = MediaCodecEncoder()
        val audioTrackParams = MediaRecordParameters(
            reader = audioTrackReader,
            targetTrack = 1,
            targetFormat = audioConfig.mediaFormat,
            mediaTarget = mediaTarget,
            encoder = audioEncoder,
            renderer = PassthroughSoftwareRenderer(audioEncoder)
        )

        requestId = UUID.randomUUID().toString()
        mediaRecorder.record(
            requestId,
            listOf(videoTrackParams, audioTrackParams)
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireActivity(), it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun tryOpenCamera(requestPermissions: Boolean = true) {
        if (allPermissionsGranted()) {
            previewSurface?.let { preview ->
                openCamera(preview, previewSurfaceSize!!)
            }
        } else if (requestPermissions) {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS)
        }
    }

    private fun openCamera(previewSurface: Surface, displaySize: Point) {
        // Create the recording surface, on the encoding thread, for which the GlVideoRenderer will be the consumer, and camera will be the producer.
        val inputSurfaceTexture = VideoRenderInputSurface.Builder().setTextureId(0).build()

        // Store reference so we can pass input surface to the encoding pipeline, once recording starts
        cameraRecordSurfaceTexture = inputSurfaceTexture

        if (cameraHandler == null) {
            val context = requireContext().applicationContext
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val thread = MultiTargetCameraThread(
                cameraManager,
                cameraListener,
                listOf(previewSurface),
                inputSurfaceTexture.surfaceTexture
            )
            thread.start()
            cameraHandler = thread.getHandler()
        }
        cameraHandler?.startPreview(displaySize.x, displaySize.y)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CAMERA_PERMISSIONS) {
            tryOpenCamera(false)
        }
    }

    // region: Event listeners

    // TODO: Handle camera events -- e.g. stop transcoding here
    private val cameraListener = object : CameraThreadListener {
        override fun onCameraStarted(previewWidth: Int, previewHeight: Int) {
            cameraPreviewSize = Point(previewWidth, previewHeight)
        }

        override fun onCameraStopped() {

        }
    }

    // endregion

    companion object {
        private val TAG = RecordCameraFragment::class.qualifiedName
        private const val REQUEST_CAMERA_PERMISSIONS = 729
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        private const val FRAME_RATE = 30
        private const val BPP = 0.25f

        fun calculateVideoBitrate(width: Int, height: Int): Int {
            val bitrate =
                (BPP * FRAME_RATE * width * height).toInt()
            Log.i(
                TAG,
                "bitrate=$bitrate"
            )
            return bitrate
        }

        fun calculateAudioBitrate(samplingFrequency: Int, channels: Int, sampleSizePerChannel: Int = 2): Int {
            return samplingFrequency * sampleSizePerChannel * channels * 8
        }
    }
}