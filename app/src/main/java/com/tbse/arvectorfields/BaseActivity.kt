package com.tbse.arvectorfields

import android.app.ProgressDialog
import android.support.annotation.VisibleForTesting
import android.support.v7.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    @VisibleForTesting
    var mProgressDialog: ProgressDialog? = null

    fun showProgressDialog() {
        if (mProgressDialog == null) {
            mProgressDialog = ProgressDialog(this)
            mProgressDialog!!.setMessage(getString(R.string.loading))
            mProgressDialog!!.isIndeterminate = true
        }

        mProgressDialog!!.show()
    }

    fun hideProgressDialog() {
        if (mProgressDialog != null && mProgressDialog!!.isShowing) {
            mProgressDialog!!.dismiss()
        }
    }

    //    public void hideKeyboard(View view) {
    //        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    //        if (imm != null) {
    //            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    //        }
    //    }

    public override fun onStop() {
        super.onStop()
        hideProgressDialog()
    }

}