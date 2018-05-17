package com.tbse.arvectorfields

/**
 * Created by toddsmith on 5/16/18.
 * Copyright TBSE 2017
 */
interface OkListener {
    /**
     * This method is called by the dialog box when its OK button is pressed.
     *
     * @param dialogValue the long value from the dialog box
     */
    fun onOkPressed(dialogValue: Long?)
}
