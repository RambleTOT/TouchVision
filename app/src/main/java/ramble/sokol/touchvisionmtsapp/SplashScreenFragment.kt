package ramble.sokol.touchvisionmtsapp

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import ramble.sokol.touchvisionmtsapp.YourAccessibilityService
import ramble.sokol.touchvisionmtsapp.databinding.FragmentSplashScreenBinding

class SplashScreenFragment : Fragment() {

    private var binding: FragmentSplashScreenBinding? = null
    private var isCheckingPermissions = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionDeniedDialog()
        } else {
            checkNextPermission()
        }
    }

    private val requestOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkNextPermission()
    }

    private val requestAccessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkNextPermission()
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkNextPermission()
    }

    private val requestManagePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkNextPermission()
    }

    private val requestUsageStatsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkNextPermission()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        binding = FragmentSplashScreenBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.buttonRegistration?.setOnClickListener {
            requestAllPermissions()
        }

        checkAllPermissions()
    }

    private fun requestAllPermissions() {
        if (isCheckingPermissions) return
        isCheckingPermissions = true
        checkNextPermission()
    }

    private fun checkNextPermission() {
        // 1. Проверяем разрешение на вибрацию
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.VIBRATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(android.Manifest.permission.VIBRATE)
            return
        }

        // 2. Проверяем разрешение на рисование поверх других приложений
        if (!Settings.canDrawOverlays(requireContext())) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")
            )
            requestOverlayPermissionLauncher.launch(intent)
            return
        }

        // 3. Проверяем разрешение USAGE_STATS (для Android 5.1+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 &&
            !hasUsageStatsPermission(requireContext())) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            requestUsageStatsLauncher.launch(intent)
            return
        }

        // 4. Проверяем разрешение для Accessibility Service
        if (!isAccessibilityServiceEnabled(requireContext(), YourAccessibilityService::class.java)) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            requestAccessibilityLauncher.launch(intent)
            return
        }

        // 5. Проверяем разрешения на работу с хранилищем
        if (!hasStoragePermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                requestStoragePermissionLauncher.launch(intent)
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            return
        }

        // 6. Проверяем дополнительные разрешения для управления приложениями (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            requestManagePermissionsLauncher.launch(intent)
            return
        }

        // Все разрешения получены
        isCheckingPermissions = false
        showSuccessDialog()
        enableTouchVisionMode()
    }

    private fun checkAllPermissions() {
        if (allPermissionsGranted()) {
            showSuccessDialog()
            enableTouchVisionMode()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val hasUsageStats = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            hasUsageStatsPermission(requireContext())
        } else {
            true
        }

        return ContextCompat.checkSelfPermission(
            requireContext(),
            android.Manifest.permission.VIBRATE
        ) == PackageManager.PERMISSION_GRANTED &&
                Settings.canDrawOverlays(requireContext()) &&
                hasUsageStats &&
                isAccessibilityServiceEnabled(requireContext(), YourAccessibilityService::class.java) &&
                hasStoragePermissions() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun enableTouchVisionMode() {
        val serviceIntent = Intent(requireContext(), YourAccessibilityService::class.java)
        requireContext().startService(serviceIntent)
    }

    private fun disableTouchVisionMode() {
        YourAccessibilityService.getInstance()?.disableService()
    }

    private fun showSuccessDialog() {
        val dialog = PermissionsSuccessDialog.newInstance()
        dialog.show(parentFragmentManager, "PermissionsSuccessDialog")
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun showPermissionDeniedDialog() {
        isCheckingPermissions = false
        AlertDialog.Builder(requireContext())
            .setTitle("Требуются разрешения")
            .setMessage("Для работы приложения необходимы все запрошенные разрешения.")
            .setPositiveButton("Повторить") { _, _ -> requestAllPermissions() }
            .setNegativeButton("Выйти") { _, _ -> requireActivity().finish() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}