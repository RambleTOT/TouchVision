package ramble.sokol.touchvisionmtsapp

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.DialogFragment
import ramble.sokol.touchvisionmtsapp.R

class PermissionsSuccessDialog : DialogFragment(){

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val view: View = inflater.inflate(R.layout.dialog_permissions_success, null)

        view.findViewById<View>(R.id.button_onboarding).setOnClickListener {
            dismiss()
            val transaction = requireActivity().supportFragmentManager.beginTransaction()
            transaction.replace(R.id.layout_fragment, OnBoardingFragment())
            transaction.disallowAddToBackStack()
            transaction.commit()
        }

        view.findViewById<View>(R.id.button_without_onboarding).setOnClickListener {
            dismiss()
            val transaction = requireActivity().supportFragmentManager.beginTransaction()
            transaction.replace(R.id.layout_fragment, MainMenuFragment())
            transaction.disallowAddToBackStack()
            transaction.commit()
        }

        builder.setView(view)
        val dialog = builder.create()
        dialog.setCancelable(false) // Запрет закрытия при нажатии вне диалога
        dialog.setCanceledOnTouchOutside(false) // Дополнительная страховка
        return dialog
    }

    companion object {
        fun newInstance() = PermissionsSuccessDialog()
    }
}