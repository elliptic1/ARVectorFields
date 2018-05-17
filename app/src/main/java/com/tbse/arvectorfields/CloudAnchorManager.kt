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

import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.Session
import java.util.*

/**
 * A helper class to handle all the Cloud Anchors logic, and add a callback-like mechanism on top of
 * the existing ARCore API.
 */
internal class CloudAnchorManager {

    private lateinit var session: Session
    private val pendingAnchors = HashMap<Anchor, CloudAnchorListener>()

    /**
     * Listener for the results of a host or resolve operation.
     */
    internal interface CloudAnchorListener {

        /**
         * This method is invoked when the results of a Cloud Anchor operation are available.
         */
        fun onCloudTaskComplete(anchor: Anchor)
    }

    /**
     * This method is used to set the session, since it might not be available when this object is
     * created.
     */
    @Synchronized
    fun setSession(session: Session) {
        this.session = session
    }

    /**
     * This method hosts an anchor. The `listener` will be invoked when the results are
     * available.
     */
    @Synchronized
    fun hostCloudAnchor(anchor: Anchor, listener: CloudAnchorListener) {
        val newAnchor = session.hostCloudAnchor(anchor)
        pendingAnchors[newAnchor] = listener
    }

    /**
     * This method resolves an anchor. The `listener` will be invoked when the results are
     * available.
     */
    @Synchronized
    fun resolveCloudAnchor(anchorId: String, listener: CloudAnchorListener) {
        val newAnchor = session.resolveCloudAnchor(anchorId)
        pendingAnchors[newAnchor] = listener
    }

    /**
     * Should be called with the updated anchors available after a [Session.update] call.
     */
    @Synchronized
    fun onUpdate(updatedAnchors: Collection<Anchor>) {
        for (anchor in updatedAnchors) {
            if (pendingAnchors.containsKey(anchor)) {
                val cloudState = anchor.cloudAnchorState
                if (isReturnableState(cloudState)) {
                    val listener = pendingAnchors.remove(anchor)
                    listener?.onCloudTaskComplete(anchor)
                }
            }
        }
    }

    /**
     * Used to clear any currently registered listeners, so they wont be called again.
     */
    @Synchronized
    fun clearListeners() {
        pendingAnchors.clear()
    }

    companion object {
        private val TAG =
                CloudAnchorActivity::class.java.simpleName + "." + CloudAnchorManager::class.java.simpleName

        private fun isReturnableState(cloudState: CloudAnchorState): Boolean {
            return when (cloudState) {
                Anchor.CloudAnchorState.NONE, Anchor.CloudAnchorState.TASK_IN_PROGRESS -> false
                else -> true
            }
        }
    }
}
