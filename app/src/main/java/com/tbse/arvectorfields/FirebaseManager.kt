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
import android.util.Log

import com.google.common.base.Preconditions
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

/**
 * A helper class to manage all communications with Firebase.
 */
internal class FirebaseManager
/**
 * Default constructor for the FirebaseManager.
 *
 * @param context The application context.
 */
(context: Context) {

    private val app: FirebaseApp?
    private val hotspotListRef: DatabaseReference?
    private val roomCodeRef: DatabaseReference?
    private var currentRoomRef: DatabaseReference? = null
    private var currentRoomListener: ValueEventListener? = null

    /**
     * Listener for a new room code.
     */
    internal interface RoomCodeListener {

        /**
         * Invoked when a new room code is available from Firebase.
         */
        fun onNewRoomCode(newRoomCode: Long?)

        /**
         * Invoked if a Firebase Database Error happened while fetching the room code.
         */
        fun onError(error: DatabaseError)
    }

    /**
     * Listener for a new cloud anchor ID.
     */
    internal interface CloudAnchorIdListener {

        /**
         * Invoked when a new cloud anchor ID is available.
         */
        fun onNewCloudAnchorId(cloudAnchorId: String)
    }

    init {
        app = FirebaseApp.initializeApp(context)
        if (app != null) {
            val rootRef = FirebaseDatabase.getInstance(app).reference
            hotspotListRef = rootRef.child(ROOT_FIREBASE_HOTSPOTS)
            roomCodeRef = rootRef.child(ROOT_LAST_ROOM_CODE)

            DatabaseReference.goOnline()
        } else {
            Log.d(TAG, "Could not connect to Firebase Database!")
            hotspotListRef = null
            roomCodeRef = null
        }
    }

    /**
     * Gets a new room code from the Firebase Database. Invokes the listener method when a new room
     * code is available.
     */
    fun getNewRoomCode(listener: RoomCodeListener) {
        Preconditions.checkNotNull<FirebaseApp>(app, "Firebase App was null")
        roomCodeRef!!.runTransaction(
                object : Transaction.Handler {
                    override fun doTransaction(currentData: MutableData): Transaction.Result {
                        var nextCode: Long? = 1L
                        val currVal = currentData.value
                        if (currVal != null) {
                            val lastCode = java.lang.Long.valueOf(currVal.toString())
                            nextCode = lastCode + 1
                        }
                        currentData.value = nextCode
                        return Transaction.success(currentData)
                    }

                    override fun onComplete(error: DatabaseError, committed: Boolean, currentData: DataSnapshot) {
                        if (!committed) {
                            listener.onError(error)
                            return
                        }
                        val roomCode = currentData.getValue(Long::class.java)
                        listener.onNewRoomCode(roomCode)
                    }
                })
    }

    /**
     * Stores the given anchor ID in the given room code.
     */
    fun storeAnchorIdInRoom(roomCode: Long?, cloudAnchorId: String) {
        Preconditions.checkNotNull<FirebaseApp>(app, "Firebase App was null")
        val roomRef = hotspotListRef?.child(roomCode.toString())
        roomRef?.child(KEY_DISPLAY_NAME)?.setValue(DISPLAY_NAME_VALUE)
        roomRef?.child(KEY_ANCHOR_ID)?.setValue(cloudAnchorId)
        roomRef?.child(KEY_TIMESTAMP)?.setValue(System.currentTimeMillis())
    }

    /**
     * Registers a new listener for the given room code. The listener is invoked whenever the data for
     * the room code is changed.
     */
    fun registerNewListenerForRoom(roomCode: Long?, listener: CloudAnchorIdListener) {
        Preconditions.checkNotNull<FirebaseApp>(app, "Firebase App was null")
        clearRoomListener()
        currentRoomRef = hotspotListRef?.child(roomCode.toString())
        currentRoomListener = object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                val valObj = dataSnapshot.child(KEY_ANCHOR_ID).value
                if (valObj != null) {
                    val anchorId = valObj.toString()
                    if (!anchorId.isEmpty()) {
                        listener.onNewCloudAnchorId(anchorId)
                    }
                }
            }

            override fun onCancelled(databaseError: DatabaseError) {
                Log.w(TAG, "The Firebase operation was cancelled.", databaseError.toException())
            }
        }
        currentRoomRef?.addValueEventListener(currentRoomListener)
    }

    /**
     * Resets the current room listener registered using [.registerNewListenerForRoom].
     */
    fun clearRoomListener() {
        if (currentRoomListener != null && currentRoomRef != null) {
            currentRoomRef!!.removeEventListener(currentRoomListener!!)
            currentRoomListener = null
            currentRoomRef = null
        }
    }

    companion object {
        private val TAG =
                CloudAnchorActivity::class.java.simpleName + "." + FirebaseManager::class.java.simpleName

        // Names of the nodes used in the Firebase Database
        private const val ROOT_FIREBASE_HOTSPOTS = "hotspot_list"
        private const val ROOT_LAST_ROOM_CODE = "last_room_code"

        // Some common keys and values used when writing to the Firebase Database.
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ANCHOR_ID = "hosted_anchor_id"
        private const val KEY_TIMESTAMP = "updated_at_timestamp"
        private const val DISPLAY_NAME_VALUE = "Android EAP Sample"
    }
}
