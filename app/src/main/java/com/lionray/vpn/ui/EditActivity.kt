package com.lionray.vpn.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lionray.vpn.R
import com.lionray.vpn.core.VpnBus
import com.lionray.vpn.core.VpnState
import com.lionray.vpn.data.ProfileStore
import com.lionray.vpn.data.ServerProfile
import com.lionray.vpn.databinding.ActivityEditBinding

/**
 * Full editor: every parameter of an imported vless key can be viewed,
 * changed and saved, or the server can be deleted.
 */
class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "profile_id"
    }

    private lateinit var binding: ActivityEditBinding
    private var profileId: Long = -1L
    private val existing: ServerProfile? get() = if (profileId > 0) ProfileStore.get(profileId) else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileId = intent.getLongExtra(EXTRA_ID, -1L)

        binding.toolbar.title =
            getString(if (existing != null) R.string.edit_server else R.string.add_server)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupSpinners()
        fillForm()
        wireListeners()
        updateVisibility()
    }

    // -------------------------------------------------------------- spinners

    private fun Spinner.fill(resId: Int) {
        adapter = ArrayAdapter.createFromResource(
            this@EditActivity, resId, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun setupSpinners() {
        binding.spFlow.fill(R.array.flow_options)
        binding.spNetwork.fill(R.array.network_options)
        binding.spHeaderType.fill(R.array.header_type_options)
        binding.spSecurity.fill(R.array.security_options)
        binding.spFingerprint.fill(R.array.fingerprint_options)
    }

    private fun idxOf(resId: Int, value: String): Int =
        resources.getStringArray(resId).indexOf(value).coerceAtLeast(0)

    // ------------------------------------------------------------------ form

    private fun fillForm() {
        val p = existing ?: return
        binding.etRemark.setText(p.remark)
        binding.etAddress.setText(p.address)
        binding.etPort.setText(p.port.toString())
        binding.etUuid.setText(p.uuid)
        // trojan/ss use a plain password where vless has a UUID
        if (!p.protocol.equals("vless", true)) {
            binding.etUuid.setHint(R.string.f_password)
        }
        binding.etEncryption.setText(p.encryption.ifBlank { "none" })
        binding.spFlow.setSelection(idxOf(R.array.flow_options, p.flow), false)
        binding.spNetwork.setSelection(idxOf(R.array.network_options, p.network), false)
        binding.spHeaderType.setSelection(idxOf(R.array.header_type_options, p.headerType), false)
        binding.spSecurity.setSelection(idxOf(R.array.security_options, p.security), false)
        binding.spFingerprint.setSelection(idxOf(R.array.fingerprint_options, p.fingerprint), false)
        binding.etHost.setText(p.host)
        binding.etPath.setText(p.path)
        binding.etServiceName.setText(p.serviceName)
        binding.etSeed.setText(p.seed)
        binding.etSni.setText(p.sni)
        binding.etAlpn.setText(p.alpn)
        binding.etPublicKey.setText(p.publicKey)
        binding.etShortId.setText(p.shortId)
        binding.etSpiderX.setText(p.spiderX)
        binding.chkAllowInsecure.isChecked = p.allowInsecure
        binding.chkMux.isChecked = p.muxEnabled
        binding.chkFragment.isChecked =
            p.fragmentLength.isNotBlank() || p.fragmentPackets.isNotBlank()
    }

    private fun wireListeners() {
        binding.spNetwork.onItemSelectedListener = spinnerListener { updateVisibility() }
        binding.spHeaderType.onItemSelectedListener = spinnerListener { updateVisibility() }
        binding.spSecurity.onItemSelectedListener = spinnerListener { updateVisibility() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnDelete.visibility =
            if (existing != null) View.VISIBLE else View.GONE

        binding.cardTransport.setOnClickListener { toggleSection(binding.layoutTransportBody, binding.tvTransportChevron) }
        binding.cardSecurity.setOnClickListener { toggleSection(binding.layoutSecurityBody, binding.tvSecurityChevron) }
        binding.cardFeatures.setOnClickListener { toggleSection(binding.layoutFeaturesBody, binding.tvFeaturesChevron) }
    }

    private fun toggleSection(body: View, chevron: android.widget.TextView) {
        if (body.visibility == View.VISIBLE) {
            body.visibility = View.GONE
            chevron.text = "▸"
        } else {
            body.visibility = View.VISIBLE
            chevron.text = "▾"
        }
    }

    private fun spinnerListener(action: () -> Unit): android.widget.AdapterView.OnItemSelectedListener =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                action()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

    /** All fields always visible — Shadowrocket-style full edit. */
    private fun updateVisibility() {
        binding.layoutHeaderType.visibility = View.VISIBLE
        binding.layoutHost.visibility = View.VISIBLE
        binding.layoutPath.visibility = View.VISIBLE
        binding.layoutServiceName.visibility = View.VISIBLE
        binding.layoutSeed.visibility = View.VISIBLE
        binding.layoutSni.visibility = View.VISIBLE
        binding.layoutFp.visibility = View.VISIBLE
        binding.layoutAlpn.visibility = View.VISIBLE
        binding.chkAllowInsecure.visibility = View.VISIBLE
        binding.layoutPublicKey.visibility = View.VISIBLE
        binding.layoutShortId.visibility = View.VISIBLE
        binding.layoutSpiderX.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ save

    private fun save() {
        val remark = binding.etRemark.text?.toString()?.trim().orEmpty()
        val address = binding.etAddress.text?.toString()?.trim().orEmpty()
        val uuid = binding.etUuid.text?.toString()?.trim().orEmpty()
        val portStr = binding.etPort.text?.toString()?.trim().orEmpty()
        val port = portStr.toIntOrNull()

        if (address.isEmpty() || uuid.isEmpty()) {
            toast(R.string.err_invalid_input)
            return
        }
        if (port == null || port < 1 || port > 65535) {
            toast(R.string.err_invalid_input)
            return
        }

        fun sp(spinner: android.widget.Spinner): String =
            spinner.selectedItem?.toString().orEmpty()

        val p = (existing ?: ServerProfile()).apply {
            id = if (profileId > 0) profileId else 0L
            this.remark = remark
            this.address = address
            this.port = port
            this.uuid = uuid
            encryption = binding.etEncryption.text?.toString()?.trim().orEmpty().ifBlank { "none" }
            flow = sp(binding.spFlow)
            network = sp(binding.spNetwork)
            headerType = sp(binding.spHeaderType)
            host = binding.etHost.text?.toString()?.trim().orEmpty()
            path = binding.etPath.text?.toString()?.trim().orEmpty()
            serviceName = binding.etServiceName.text?.toString()?.trim().orEmpty()
            seed = binding.etSeed.text?.toString()?.trim().orEmpty()
            security = sp(binding.spSecurity).ifBlank { "none" }
            sni = binding.etSni.text?.toString()?.trim().orEmpty()
            fingerprint = sp(binding.spFingerprint)
            publicKey = binding.etPublicKey.text?.toString()?.trim().orEmpty()
            shortId = binding.etShortId.text?.toString()?.trim().orEmpty()
            spiderX = binding.etSpiderX.text?.toString()?.trim().orEmpty()
            alpn = binding.etAlpn.text?.toString()?.trim().orEmpty()
            allowInsecure = binding.chkAllowInsecure.isChecked
            muxEnabled = binding.chkMux.isChecked
            if (binding.chkFragment.isChecked) {
                // keep URI-provided ranges when present, else sane defaults
                fragmentPackets = fragmentPackets.ifBlank { "tlshello" }
                fragmentLength = fragmentLength.ifBlank { "40-60" }
                fragmentInterval = fragmentInterval.ifBlank { "30-50" }
            } else {
                fragmentPackets = ""
                fragmentLength = ""
                fragmentInterval = ""
            }
        }

        ProfileStore.upsert(p)
        toast(R.string.saved_ok)
        if (p.id == ProfileStore.activeId.value && VpnBus.state.value == VpnState.CONNECTED) {
            toast(getString(R.string.toast_reconnect_needed))
        }
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ask_delete_title)
            .setMessage(R.string.ask_delete_msg_edit)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                existing?.let { ProfileStore.delete(it.id) }
                toast(R.string.deleted_ok)
                finish()
            }
            .show()
    }

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
}

