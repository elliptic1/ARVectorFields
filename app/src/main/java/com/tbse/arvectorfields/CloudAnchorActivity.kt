/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

package com.tbse.arvectorfields

import android.content.Context
import android.content.Intent
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.support.annotation.GuardedBy
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.Config.CloudAnchorMode
import com.google.ar.core.Point.OrientationMode
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.common.base.Preconditions
import com.google.firebase.database.DatabaseError
import com.tbse.arvectorfields.common.helpers.CameraPermissionHelper
import com.tbse.arvectorfields.common.helpers.DisplayRotationHelper
import com.tbse.arvectorfields.common.helpers.FullScreenHelper
import com.tbse.arvectorfields.common.helpers.SnackbarHelper
import com.tbse.arvectorfields.common.rendering.BackgroundRenderer
import com.tbse.arvectorfields.common.rendering.ObjectRenderer
import com.tbse.arvectorfields.common.rendering.PlaneRenderer
import com.tbse.arvectorfields.common.rendering.PointCloudRenderer
import kotlinx.android.synthetic.main.activity_main.*
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Main Activity for the Cloud Anchor Example
 *
 *
 *
 * This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
class CloudAnchorActivity : AppCompatActivity(), GLSurfaceView.Renderer, OkListener {

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private val backgroundRenderer = BackgroundRenderer()
    private val virtualObject = ObjectRenderer()
    private val virtualObjectShadow = ObjectRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()

    private var installRequested: Boolean = false

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Locks needed for synchronization
    private val singleTapLock = Any()
    private val anchorLock = Any()

    // Tap handling and UI.
    private var gestureDetector: GestureDetector? = null
    private val snackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null

    private var session: Session? = null

    @GuardedBy("anchorLock")
    private var anchor: Anchor? = null

    // Cloud Anchor Components.
    private lateinit var firebaseManager: FirebaseManager
    private val cloudManager = CloudAnchorManager()
    private var currentMode: HostResolveMode = HostResolveMode.NONE
    private var hostListener: RoomCodeAndCloudAnchorIdListener? = null

    private enum class HostResolveMode {
        NONE,
        HOSTING,
        RESOLVING
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        displayRotationHelper = DisplayRotationHelper(this)

        // Set up tap listener.
        gestureDetector = GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        synchronized(singleTapLock) {
                            if (currentMode == HostResolveMode.HOSTING) {
                                queuedSingleTap = e
                            }
                        }
                        return true
                    }

                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }
                })
        surfaceview.setOnTouchListener { v, event -> gestureDetector!!.onTouchEvent(event) }

        // Set up renderer.
        surfaceview.preserveEGLContextOnPause = true
        surfaceview.setEGLContextClientVersion(2)
        surfaceview.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.
        surfaceview.setRenderer(this)
        surfaceview.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        installRequested = false

        // Initialize UI components.
        host_button.setOnClickListener { view -> onHostButtonPress() }
        resolve_button.setOnClickListener { view -> onResolveButtonPress() }

        // Initialize Cloud Anchor variables.
        firebaseManager = FirebaseManager(this)

        if (session == null) {
            var exception: Exception? = null
            var messageId = -1
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                    }
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }
                session = Session(this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                messageId = R.string.snackbar_arcore_unavailable
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                messageId = R.string.snackbar_arcore_too_old
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                messageId = R.string.snackbar_arcore_sdk_too_old
                exception = e
            } catch (e: Exception) {
                messageId = R.string.snackbar_arcore_exception
                exception = e
            }

            if (exception != null) {
                snackbarHelper.showError(this, getString(messageId))
                Log.e(TAG, "Exception creating session", exception)
                return
            }

            // Create default config and check if supported.
            val config = Config(session!!)
            config.cloudAnchorMode = CloudAnchorMode.ENABLED
            session!!.configure(config)

            // Setting the session in the HostManager.
            cloudManager.setSession(session!!)
            // Show the inital message only in the first resume.
            snackbarHelper.showMessage(this, getString(R.string.snackbar_initial_message))
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            // In some cases (such as another camera app launching) the camera may be given to
            // a different app instead. Handle this properly by showing a message and recreate the
            // session at the next iteration.
            snackbarHelper.showError(this, getString(R.string.snackbar_camera_unavailable))
            session = null
            return
        }

        surfaceview.onResume()
        displayRotationHelper!!.onResume()
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceview.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    /**
     * Handles the most recent user tap.
     *
     *
     *
     * We only ever handle one tap at a time, since this app only allows for a single anchor.
     *
     * @param frame               the current AR frame
     * @param cameraTrackingState the current camera tracking state
     */
    private fun handleTap(frame: Frame, cameraTrackingState: TrackingState) {
        // Handle taps. Handling only one tap per frame, as taps are usually low frequency
        // compared to frame rate.
        synchronized(singleTapLock) {
            synchronized(anchorLock) {
                // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
                // camera is currently tracking.
                if (anchor == null
                        && queuedSingleTap != null
                        && cameraTrackingState == TrackingState.TRACKING) {
                    Preconditions.checkState(
                            currentMode == HostResolveMode.HOSTING,
                            "We should only be creating an anchor in hosting mode.")
                    for (hit in frame.hitTest(queuedSingleTap!!)) {
                        if (shouldCreateAnchorWithHit(hit)) {
                            val newAnchor = hit.createAnchor()
                            Preconditions.checkNotNull<RoomCodeAndCloudAnchorIdListener>(hostListener, "The host listener cannot be null.")
                            cloudManager.hostCloudAnchor(newAnchor, hostListener!!)
                            setNewAnchor(newAnchor)
                            snackbarHelper.showMessage(this, getString(R.string.snackbar_anchor_placed))
                            break // Only handle the first valid hit.
                        }
                    }
                }
            }
            queuedSingleTap = null
        }
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(this)
            planeRenderer.createOnGlThread(this, "models/trigrid.png")
            pointCloudRenderer.createOnGlThread(this)

            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)

            virtualObjectShadow.createOnGlThread(
                    this, "models/andy_shadow.obj", "models/andy_shadow.png")
            virtualObjectShadow.setBlendMode(ObjectRenderer.BlendMode.Shadow)
            virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f)
        } catch (ex: IOException) {
            Log.e(TAG, "Failed to read an asset file", ex)
        }

    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) {
            return
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session!!)

        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            val frame = session!!.update()
            val camera = frame.camera
            val updatedAnchors = frame.updatedAnchors
            val cameraTrackingState = camera.trackingState

            // Notify the cloudManager of all the updates.
            cloudManager.onUpdate(updatedAnchors)

            // Handle user input.
            handleTap(frame, cameraTrackingState)

            // Draw background.
            backgroundRenderer.draw(frame)

            // If not tracking, don't draw 3d objects.
            if (cameraTrackingState == TrackingState.PAUSED) {
                return
            }

            // Get camera and projection matrices.
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            // Visualize tracked points.
            val pointCloud = frame.acquirePointCloud()
            pointCloudRenderer.update(pointCloud)
            pointCloudRenderer.draw(viewMatrix, projectionMatrix)

            // Application is responsible for releasing the point cloud resources after using it.
            pointCloud.release()

            // Visualize planes.
            planeRenderer.drawPlanes(
                    session!!.getAllTrackables(Plane::class.java), camera.displayOrientedPose, projectionMatrix)

            // Check if the anchor can be visualized or not, and get its pose if it can be.
            var shouldDrawAnchor = false
            synchronized(anchorLock) {
                if (anchor != null && anchor!!.trackingState == TrackingState.TRACKING) {
                    // Get the current pose of an Anchor in world space. The Anchor pose is updated
                    // during calls to session.update() as ARCore refines its estimate of the world.
                    anchor!!.pose.toMatrix(anchorMatrix, 0)
                    shouldDrawAnchor = true
                }
            }

            // Visualize anchor.
            if (shouldDrawAnchor) {
                val colorCorrectionRgba = FloatArray(4)
                frame.lightEstimate.getColorCorrection(colorCorrectionRgba, 0)

                // Update and draw the model and its shadow.
                val scaleFactor = 1.0f
                virtualObject.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor)
                virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba)
                virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba)
            }
        } catch (t: Throwable) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }

    }

    /**
     * Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.
     */
    private fun setNewAnchor(newAnchor: Anchor?) {
        synchronized(anchorLock) {
            if (anchor != null) {
                anchor!!.detach()
            }
            anchor = newAnchor
        }
    }

    /**
     * Callback function invoked when the Host Button is pressed.
     */
    private fun onHostButtonPress() {
        if (currentMode == HostResolveMode.HOSTING) {
            resetMode()
            return
        }

        if (hostListener != null) {
            return
        }
        resolve_button.isEnabled = false
        host_button.setText(R.string.cancel)
        snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_host))

        hostListener = RoomCodeAndCloudAnchorIdListener()
        firebaseManager.getNewRoomCode(hostListener!!)
    }

    /**
     * Callback function invoked when the Resolve Button is pressed.
     */
    private fun onResolveButtonPress() {
        if (currentMode == HostResolveMode.RESOLVING) {
            resetMode()
            return
        }
        val dialogFragment = ResolveDialogFragment()
        dialogFragment.setOkListener(this)
        dialogFragment.show(supportFragmentManager, "ResolveDialog")
    }

    override fun onOkPressed(dialogValue: Long?) {
        onRoomCodeEntered(dialogValue)
    }

    /**
     * Resets the mode of the app to its initial state and removes the anchors.
     */
    private fun resetMode() {
        host_button.setText(R.string.host_button_text)
        host_button.isEnabled = true
        resolve_button.setText(R.string.resolve_button_text)
        resolve_button.isEnabled = true
        room_code_text.setText(R.string.initial_room_code)
        currentMode = HostResolveMode.NONE
        firebaseManager.clearRoomListener()
        hostListener = null
        setNewAnchor(null)
        snackbarHelper.hide(this)
        cloudManager.clearListeners()
    }

    /**
     * Callback function invoked when the user presses the OK button in the Resolve Dialog.
     */
    private fun onRoomCodeEntered(roomCode: Long?) {
        currentMode = HostResolveMode.RESOLVING
        host_button.isEnabled = false
        resolve_button.setText(R.string.cancel)
        room_code_text.text = roomCode.toString()
        snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_resolve))

        // Register a new listener for the given room.
        firebaseManager.registerNewListenerForRoom(
                roomCode,
                object : FirebaseManager.CloudAnchorIdListener {
                    override fun onNewCloudAnchorId(cloudAnchorId: String) {
                        // When the cloud anchor ID is available from Firebase.
                        cloudManager.resolveCloudAnchor(
                                cloudAnchorId,
                                object : CloudAnchorManager.CloudAnchorListener {
                                    override fun onCloudTaskComplete(anchor: Anchor) {
                                        // When the anchor has been resolved, or had a final error state.
                                        val cloudState = anchor.cloudAnchorState
                                        if (cloudState.isError) {
                                            Log.w(
                                                    TAG,
                                                    "The anchor in room "
                                                            + roomCode
                                                            + " could not be resolved. The error state was "
                                                            + cloudState)
                                            snackbarHelper.showMessageWithDismiss(
                                                    this@CloudAnchorActivity,
                                                    getString(R.string.snackbar_resolve_error, cloudState))
                                            return
                                        }
                                        snackbarHelper.showMessageWithDismiss(
                                                this@CloudAnchorActivity, getString(R.string.snackbar_resolve_success))
                                        setNewAnchor(anchor)
                                    }
                                })
                    }
                }
        )
    }

    /**
     * Listens for both a new room code and an anchor ID, and shares the anchor ID in Firebase with
     * the room code when both are available.
     */
    private inner class RoomCodeAndCloudAnchorIdListener : CloudAnchorManager.CloudAnchorListener, FirebaseManager.RoomCodeListener {

        private var roomCode: Long? = null
        private var cloudAnchorId: String? = null

        override fun onNewRoomCode(newRoomCode: Long?) {
            Preconditions.checkState(roomCode == null, "The room code cannot have been set before.")
            roomCode = newRoomCode
            room_code_text.text = roomCode.toString()
            snackbarHelper.showMessageWithDismiss(
                    this@CloudAnchorActivity, getString(R.string.snackbar_room_code_available))
            checkAndMaybeShare()
            synchronized(singleTapLock) {
                // Change currentMode to HOSTING after receiving the room code (not when the 'Host' button
                // is tapped), to prevent an anchor being placed before we know the room code and able to
                // share the anchor ID.
                currentMode = HostResolveMode.HOSTING
            }
        }

        override fun onError(error: DatabaseError) {
            Log.w(TAG, "A Firebase database error happened.", error.toException())
            snackbarHelper.showError(
                    this@CloudAnchorActivity, getString(R.string.snackbar_firebase_error))
        }

        override fun onCloudTaskComplete(anchor: Anchor) {
            val cloudState = anchor.cloudAnchorState
            if (cloudState.isError) {
                Log.e(TAG, "Error hosting a cloud anchor, state $cloudState")
                snackbarHelper.showMessageWithDismiss(
                        this@CloudAnchorActivity, getString(R.string.snackbar_host_error, cloudState))
                return
            }
            Preconditions.checkState(
                    cloudAnchorId == null, "The cloud anchor ID cannot have been set before.")
            cloudAnchorId = anchor.cloudAnchorId
            setNewAnchor(anchor)
            checkAndMaybeShare()
        }

        private fun checkAndMaybeShare() {
            if (roomCode == null || cloudAnchorId == null) {
                return
            }
            firebaseManager.storeAnchorIdInRoom(roomCode, cloudAnchorId!!)
            snackbarHelper.showMessageWithDismiss(
                    this@CloudAnchorActivity, getString(R.string.snackbar_cloud_id_shared))
        }
    }

    companion object {
        private val TAG = CloudAnchorActivity::class.java.simpleName

        /**
         * Returns `true` if and only if the hit can be used to create an Anchor reliably.
         */
        private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean {
            val trackable = hit.trackable
            if (trackable is Plane) {
                // Check if the hit was within the plane's polygon.
                return trackable.isPoseInPolygon(hit.hitPose)
            } else if (trackable is Point) {
                // Check if the hit was against an oriented point.
                return trackable.orientationMode == OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            return false
        }

        fun newIntent(context: Context): Intent {
            return Intent(context, CloudAnchorActivity::class.java)
        }
    }
}
