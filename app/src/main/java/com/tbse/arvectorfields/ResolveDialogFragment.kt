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

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v4.app.FragmentActivity
import android.text.Editable
import android.view.View
import android.widget.EditText

import com.google.common.base.Preconditions

/** A DialogFragment for the Resolve Dialog Box.  */
class ResolveDialogFragment : DialogFragment() {

    private var roomCodeField: EditText? = null
    private var okListener: OkListener? = null

    interface OkListener {
        /**
         * This method is called by the dialog box when its OK button is pressed.
         *
         * @param dialogValue the long value from the dialog box
         */
        fun onOkPressed(dialogValue: Long?)
    }

    fun setOkListener(okListener: OkListener) {
        this.okListener = okListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = Preconditions.checkNotNull<FragmentActivity>(activity, "The activity cannot be null.")
        val builder = AlertDialog.Builder(activity)

        // Passing null as the root is fine, because the view is for a dialog.
        val dialogView = activity.layoutInflater.inflate(R.layout.resolve_dialog, null)
        roomCodeField = dialogView.findViewById(R.id.room_code_input)
        builder
                .setView(dialogView)
                .setTitle(R.string.resolve_dialog_title)
                .setPositiveButton(
                        R.string.resolve_dialog_ok
                ) { dialog, which ->
                    val roomCodeText = roomCodeField!!.text
                    if (okListener != null && roomCodeText != null && roomCodeText.length > 0) {
                        val longVal = java.lang.Long.valueOf(roomCodeText.toString())
                        okListener!!.onOkPressed(longVal)
                    }
                }
                .setNegativeButton(R.string.cancel) { dialog, which -> }
        return builder.create()
    }
}
