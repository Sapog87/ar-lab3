/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.kotlin.helloar

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.GLError
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.sqrt

/** Renders the HelloAR application using our example Renderer. */
class HelloArRenderer(val activity: HelloArActivity) :
    SampleRender.Renderer, DefaultLifecycleObserver {
    companion object {
        const val TAG = "HelloArRenderer"

        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100f

        const val CUBEMAP_RESOLUTION = 16
        const val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32
    }

    lateinit var render: SampleRender
    lateinit var planeRenderer: PlaneRenderer
    lateinit var backgroundRenderer: BackgroundRenderer
    lateinit var virtualSceneFramebuffer: Framebuffer
    var hasSetTextureNames = false

    lateinit var pointCloudVertexBuffer: VertexBuffer
    lateinit var pointCloudMesh: Mesh
    lateinit var pointCloudShader: Shader

    var lastPointCloudTimestamp: Long = 0

    lateinit var virtualObjectMesh: Mesh
    lateinit var virtualObjectShader: Shader
    lateinit var virtualObjectAlbedoTexture: Texture
    lateinit var virtualObjectAlbedoInstantPlacementTexture: Texture

    private val wrappedAnchors = mutableListOf<WrappedAnchor>()

    lateinit var dfgTexture: Texture
    lateinit var cubemapFilter: SpecularCubemapFilter

    val modelMatrix = FloatArray(16)
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val modelViewMatrix = FloatArray(16)

    val modelViewProjectionMatrix = FloatArray(16)

    val session
        get() = activity.arCoreSessionHelper.session

    val displayRotationHelper = DisplayRotationHelper(activity)
    val trackingStateHelper = TrackingStateHelper(activity)

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper.onResume()
        hasSetTextureNames = false
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper.onPause()
    }

    override fun onSurfaceCreated(render: SampleRender) {
        try {
            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

            cubemapFilter =
                SpecularCubemapFilter(
                    render,
                    CUBEMAP_RESOLUTION,
                    CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES
                )
            dfgTexture =
                Texture(
                    render,
                    Texture.Target.TEXTURE_2D,
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    /*useMipmaps=*/ false
                )
            val dfgResolution = 64
            val dfgChannels = 2
            val halfFloatSize = 2

            val buffer: ByteBuffer =
                ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
            activity.assets.open("models/dfg.raw").use { it.read(buffer.array()) }

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.textureId)
            GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                /*level=*/ 0,
                GLES30.GL_RG16F,
                /*width=*/ dfgResolution,
                /*height=*/ dfgResolution,
                /*border=*/ 0,
                GLES30.GL_RG,
                GLES30.GL_HALF_FLOAT,
                buffer
            )
            GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

            pointCloudShader =
                Shader.createFromAssets(
                    render,
                    "shaders/point_cloud.vert",
                    "shaders/point_cloud.frag",
                    /*defines=*/ null
                )
                    .setVec4(
                        "u_Color",
                        floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f)
                    )
                    .setFloat("u_PointSize", 5.0f)

            pointCloudVertexBuffer =
                VertexBuffer(render, /*numberOfEntriesPerVertex=*/ 4, /*entries=*/ null)
            val pointCloudVertexBuffers = arrayOf(pointCloudVertexBuffer)
            pointCloudMesh =
                Mesh(
                    render,
                    Mesh.PrimitiveMode.POINTS, /*indexBuffer=*/
                    null,
                    pointCloudVertexBuffers
                )

            virtualObjectAlbedoTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_albedo.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.SRGB
                )

            val virtualObjectPbrTexture =
                Texture.createFromAsset(
                    render,
                    "models/pawn_roughness_metallic_ao.png",
                    Texture.WrapMode.CLAMP_TO_EDGE,
                    Texture.ColorFormat.LINEAR
                )
            virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj")
            virtualObjectShader =
                Shader.createFromAssets(
                    render,
                    "shaders/environmental_hdr.vert",
                    "shaders/environmental_hdr.frag",
                    mapOf("NUMBER_OF_MIPMAP_LEVELS" to cubemapFilter.numberOfMipmapLevels.toString())
                )
                    .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                    .setTexture(
                        "u_RoughnessMetallicAmbientOcclusionTexture",
                        virtualObjectPbrTexture
                    )
                    .setTexture("u_Cubemap", cubemapFilter.filteredCubemapTexture)
                    .setTexture("u_DfgTexture", dfgTexture)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        virtualSceneFramebuffer.resize(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        val session = session ?: return

        if (!hasSetTextureNames) {
            session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        displayRotationHelper.updateSessionIfNeeded(session)

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            showError("Camera not available. Try restarting the app.")
            return
        }

        val camera = frame.camera

        try {
            backgroundRenderer.setUseDepthVisualization(
                render,
                activity.depthSettings.depthColorVisualizationEnabled()
            )
            backgroundRenderer.setUseOcclusion(
                render,
                activity.depthSettings.useDepthForOcclusion()
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            showError("Failed to read a required asset file: $e")
            return
        }

        backgroundRenderer.updateDisplayGeometry(frame)
        val shouldGetDepthImage =
            activity.depthSettings.useDepthForOcclusion() ||
                    activity.depthSettings.depthColorVisualizationEnabled()
        if (camera.trackingState == TrackingState.TRACKING && shouldGetDepthImage) {
            try {
                val depthImage = frame.acquireDepthImage16Bits()
                backgroundRenderer.updateCameraDepthTexture(depthImage)
                depthImage.close()
            } catch (_: NotYetAvailableException) {
            }
        }

        handleTap(frame, camera)

        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

        val message: String? =
            when {
                camera.trackingState == TrackingState.PAUSED &&
                        camera.trackingFailureReason == TrackingFailureReason.NONE ->
                    activity.getString(R.string.searching_planes)

                camera.trackingState == TrackingState.PAUSED ->
                    TrackingStateHelper.getTrackingFailureReasonString(camera)

                session.hasTrackingPlane() && wrappedAnchors.isEmpty() ->
                    activity.getString(R.string.waiting_taps)

                session.hasTrackingPlane() && wrappedAnchors.isNotEmpty() -> null
                else -> activity.getString(R.string.searching_planes)
            }
        if (message == null) {
            activity.view.snackbarHelper.hide(activity)
        } else {
            activity.view.snackbarHelper.showMessage(activity, message)
        }

        if (frame.timestamp != 0L) {
            backgroundRenderer.drawBackground(render)
        }

        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        camera.getViewMatrix(viewMatrix, 0)
        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.timestamp > lastPointCloudTimestamp) {
                pointCloudVertexBuffer.set(pointCloud.points)
                lastPointCloudTimestamp = pointCloud.timestamp
            }
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(pointCloudMesh, pointCloudShader)
        }

        planeRenderer.drawPlanes(
            render,
            session.getAllTrackables(Plane::class.java),
            camera.displayOrientedPose,
            projectionMatrix
        )

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        for ((anchor, trackable) in
        wrappedAnchors.filter { it.anchor.trackingState == TrackingState.TRACKING }) {
            anchor.pose.toMatrix(modelMatrix, 0)

            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            virtualObjectShader.setMat4("u_ModelView", modelViewMatrix)
            virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            val texture = virtualObjectAlbedoTexture
            virtualObjectShader.setTexture("u_AlbedoTexture", texture)
            render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
        }

        // Compose the virtual scene with the background.
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }

    /** Checks if we detected at least one plane. */
    private fun Session.hasTrackingPlane() =
        getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }

    private fun handleTap(frame: Frame, camera: Camera) {
        if (camera.trackingState != TrackingState.TRACKING) return
        val tap = activity.view.tapHelper.poll() ?: return

        val hitResultList = frame.hitTest(tap)

        val hitPose = hitResultList.firstOrNull()?.hitPose ?: return

        val tapPosition = FloatArray(3)
        hitPose.getTranslation(tapPosition, 0)
        val hitRadiusMeters = 0.15f

        // Проверяем, попали ли в один из существующих объектов
        val hitAnchor = wrappedAnchors.firstOrNull { wrapped ->
            val anchorPose = wrapped.anchor.pose
            val anchorPos = FloatArray(3)
            anchorPose.getTranslation(anchorPos, 0)
            val dx = anchorPos[0] - tapPosition[0]
            val dy = anchorPos[1] - tapPosition[1]
            val dz = anchorPos[2] - tapPosition[2]
            val distance = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            distance < hitRadiusMeters
        }

        if (hitAnchor != null) {
            // Показываем меню, связанное с этим объектом
            activity.runOnUiThread {
                activity.showAnchorMenu(hitAnchor)
            }
            return
        }

        // Если не попали по объекту — добавляем новый
        val firstHitResult =
            hitResultList.firstOrNull { hit ->
                when (val trackable = hit.trackable!!) {
                    is Plane ->
                        trackable.isPoseInPolygon(hit.hitPose) &&
                                PlaneRenderer.calculateDistanceToPlane(hit.hitPose, camera.pose) > 0

                    is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    is InstantPlacementPoint -> true
                    is DepthPoint -> true
                    else -> false
                }
            }

        if (firstHitResult != null) {
            if (wrappedAnchors.size >= 20) {
                wrappedAnchors[0].anchor.detach()
                wrappedAnchors.removeAt(0)
            }
            wrappedAnchors.add(
                WrappedAnchor(
                    firstHitResult.createAnchor(),
                    firstHitResult.trackable
                )
            )
        }
    }

    private fun showError(errorMessage: String) =
        activity.view.snackbarHelper.showError(activity, errorMessage)

    fun removeAnchor(wrappedAnchor: WrappedAnchor) {
        wrappedAnchors.remove(wrappedAnchor)
    }
}

data class WrappedAnchor(
    val anchor: Anchor,
    val trackable: Trackable,
)
