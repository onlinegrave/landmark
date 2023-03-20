package com.mapking.ar.core.landmark.geo.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.mapking.ar.core.landmark.geo.R

class AddLineResult(lat: Double, lng: Double, length: Double)

class AddLineDialog : DialogFragment() {

    internal lateinit var listener: AddLineDialogListener

    interface AddLineDialogListener {
        fun onPositiveDialogClick(result: AddLineResult)
        fun onNegativeDialogClick()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            listener = context as AddLineDialogListener
        } catch (e: ClassCastException) {
            throw ClassCastException(("$context must implement AddLineDialogListener"))
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_add_line_dialog, container, false)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = MaterialAlertDialogBuilder(requireContext())
        val view = View.inflate(requireContext(), R.layout.fragment_add_line_dialog, null)
        dialog.setView(view)
        val etLongitude = view.findViewById<TextInputEditText>(R.id.et_longitude)
        val etLatitude = view.findViewById<TextInputEditText>(R.id.et_latitude)
        val etLength = view.findViewById<TextInputEditText>(R.id.et_length)
        val etBearing = view.findViewById<TextInputEditText>(R.id.et_bearing)


        dialog.setPositiveButton("Add") { dialog, id ->
            listener.onPositiveDialogClick(
                AddLineResult(
                    etLatitude.text.toString().toDouble(),
                    etLongitude.text.toString().toDouble(),
                    etLength.text.toString().toDouble()
                )
            )
        }

        dialog.setNegativeButton("Cancel") { dialog, id ->
            listener.onNegativeDialogClick()
        }

        return dialog.create();
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: ")
    }

    companion object {
        const val TAG = "AddLineDialog"
    }
}