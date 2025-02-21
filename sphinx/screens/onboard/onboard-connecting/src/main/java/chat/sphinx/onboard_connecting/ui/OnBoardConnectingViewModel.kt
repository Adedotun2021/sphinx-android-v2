package chat.sphinx.onboard_connecting.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import chat.sphinx.concept_network_query_contact.NetworkQueryContact
import chat.sphinx.concept_network_query_invite.NetworkQueryInvite
import chat.sphinx.concept_repository_connect_manager.ConnectManagerRepository
import chat.sphinx.concept_repository_connect_manager.model.OwnerRegistrationState
import chat.sphinx.concept_signer_manager.CheckAdminCallback
import chat.sphinx.concept_signer_manager.SignerManager
import chat.sphinx.concept_wallet.WalletDataHandler
import chat.sphinx.example.wrapper_mqtt.ConnectManagerError
import chat.sphinx.key_restore.KeyRestore
import chat.sphinx.key_restore.KeyRestoreResponse
import chat.sphinx.kotlin_response.LoadResponse
import chat.sphinx.kotlin_response.Response
import chat.sphinx.onboard_common.OnBoardStepHandler
import chat.sphinx.onboard_common.model.RedemptionCode
import chat.sphinx.onboard_connecting.navigation.OnBoardConnectingNavigator
import chat.sphinx.wrapper_invite.InviteString
import chat.sphinx.wrapper_invite.toValidInviteStringOrNull
import chat.sphinx.wrapper_relay.toRelayUrl
import chat.sphinx.wrapper_rsa.RsaPrivateKey
import chat.sphinx.wrapper_rsa.RsaPublicKey
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.matthewnelson.android_feature_navigation.util.navArgs
import io.matthewnelson.android_feature_viewmodel.MotionLayoutViewModel
import io.matthewnelson.android_feature_viewmodel.submitSideEffect
import io.matthewnelson.android_feature_viewmodel.updateViewState
import io.matthewnelson.concept_coroutines.CoroutineDispatchers
import io.matthewnelson.crypto_common.annotations.RawPasswordAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.annotation.meta.Exhaustive
import javax.inject.Inject
import chat.sphinx.resources.R as R_common

internal inline val OnBoardConnectingFragmentArgs.restoreCode: RedemptionCode.AccountRestoration?
    get() {
        argCode?.let {
            val redemptionCode = RedemptionCode.decode(it)

            if (redemptionCode is RedemptionCode.AccountRestoration) {
                return redemptionCode
            }
        }
        return null
    }

internal inline val OnBoardConnectingFragmentArgs.inviteCode: InviteString?
    get() {
        argCode?.let {
            return it.toValidInviteStringOrNull()
        }
        return null
    }

@HiltViewModel
internal class OnBoardConnectingViewModel @Inject constructor(
    dispatchers: CoroutineDispatchers,
    handle: SavedStateHandle,
    val navigator: OnBoardConnectingNavigator,
    private val keyRestore: KeyRestore,
    private val walletDataHandler: WalletDataHandler,
    private val networkQueryInvite: NetworkQueryInvite,
    private val networkQueryContact: NetworkQueryContact,
    private val connectManagerRepository: ConnectManagerRepository,
    private val onBoardStepHandler: OnBoardStepHandler,
    val moshi: Moshi,
    private val app: Application
    ): MotionLayoutViewModel<
        Any,
        Context,
        OnBoardConnectingSideEffect,
        OnBoardConnectingViewState
        >(dispatchers, OnBoardConnectingViewState.Connecting)
{

    private val args: OnBoardConnectingFragmentArgs by handle.navArgs()
    private lateinit var signerManager: SignerManager

    private val userStateSharedPreferences: SharedPreferences =
        app.getSharedPreferences(USER_STATE_SHARED_PREFERENCES, Context.MODE_PRIVATE)

    private val serverSettingsSharedPreferences: SharedPreferences =
        app.getSharedPreferences(SERVER_SETTINGS_SHARED_PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        const val USER_STATE_SHARED_PREFERENCES = "user_state_settings"
        const val SERVER_SETTINGS_SHARED_PREFERENCES = "server_ip_settings"
        const val ONION_STATE_KEY = "onion_state"
        const val NETWORK_MIXER_IP = "network_mixer_ip"
        const val TRIBE_SERVER_IP = "tribe_server_ip"
        const val DEFAULT_TRIBE_KEY = "default_tribe"

        const val ENVIRONMENT_TYPE = "environment_type"
        const val ROUTER_URL= "router_url"
    }

    init {
        collectUserState()
        collectConnectionState()
        collectConnectManagerErrorState()

        viewModelScope.launch(mainImmediate) {
            delay(500L)
            connectManagerRepository.createOwnerAccount()
        }
    }

    fun setSignerManager(signerManager: SignerManager) {
        signerManager.setWalletDataHandler(walletDataHandler)
        signerManager.setMoshi(moshi)
//        signerManager.setNetworkQueryContact(networkQueryContact)

        this.signerManager = signerManager
    }

    private fun storeUserState(state: String) {
        val editor = userStateSharedPreferences.edit()

        editor.putString(ONION_STATE_KEY, state)
        editor.apply()
    }

    private fun storeNetworkMixerIp(state: String) {
        val editor = serverSettingsSharedPreferences.edit()

        editor.putString(NETWORK_MIXER_IP, state)
        editor.apply()
    }

    private fun storeTribeServerIp(state: String) {
        val editor = serverSettingsSharedPreferences.edit()

        editor.putString(TRIBE_SERVER_IP, state)
        editor.apply()
    }

    private fun storeDefaultTribe(state: String) {
        val editor = serverSettingsSharedPreferences.edit()

        editor.putString(DEFAULT_TRIBE_KEY, state)
        editor.apply()
    }

    private fun storeEnvironmentType(state: Boolean) {
        val editor = serverSettingsSharedPreferences.edit()

        editor.putBoolean(ENVIRONMENT_TYPE, state)
        editor.apply()
    }

    private fun storeRouterUrl(state: String?) {
        val editor = serverSettingsSharedPreferences.edit()

        editor.putString(ROUTER_URL, state)
        editor.apply()
    }


    private fun fetchMissingAccountConfig(
        isProductionEnvironment: Boolean,
        routerUrl: String?,
        tribeServerHost: String?,
        defaultTribe: String?,
        isRestore: Boolean
    ) {
        viewModelScope.launch(mainImmediate) {
            networkQueryContact.getAccountConfig(isProductionEnvironment).collect { loadResponse ->
                when (loadResponse) {
                    is Response.Success -> {
                        loadResponse.value.router.takeIf { it.isNotEmpty() && routerUrl.isNullOrEmpty() }?.let {
                            storeRouterUrl(it)
                        }
                        loadResponse.value.tribe_host.takeIf { it.isNotEmpty() && tribeServerHost.isNullOrEmpty() }?.let {
                            storeTribeServerIp(it)
                        }
                        loadResponse.value.tribe.takeIf { it.isNotEmpty() && defaultTribe.isNullOrEmpty() }?.let {
                            storeDefaultTribe(it)
                        }
                        delay(100L)

                        if (isRestore) {
                            navigator.toOnBoardDesktopScreen()
                        } else {
                            navigator.toOnBoardNameScreen()
                        }
                    }
                    is Response.Error -> {
                        submitSideEffect(
                            OnBoardConnectingSideEffect.Notify(
                                app.getString(R_common.string.connect_manager_set_router_url)
                            )
                        )
                        if (isRestore) {
                            navigator.toOnBoardDesktopScreen()
                        } else {
                            navigator.toOnBoardNameScreen()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private var decryptionJob: Job? = null
    @OptIn(RawPasswordAccess::class)
    fun decryptInput(viewState: OnBoardConnectingViewState.Set2_DecryptKeys) {
        // TODO: Replace with automatic launching upon entering the 6th PIN character
        //  when Authentication View's Layout gets incorporated
        if (viewState.pinWriter.size() != 6 /*TODO: https://github.com/stakwork/sphinx-kotlin/issues/9*/) {
            viewModelScope.launch(mainImmediate) {
                submitSideEffect(OnBoardConnectingSideEffect.InvalidPinLength)
            }
        }

        if (decryptionJob?.isActive == true) {
            return
        }

        var decryptionJobException: Exception? = null
        decryptionJob = viewModelScope.launch(default) {
            try {
                val pin = viewState.pinWriter.toCharArray()

                val decryptedCode: RedemptionCode.AccountRestoration.DecryptedRestorationCode =
                    viewState.restoreCode.decrypt(pin, dispatchers)

                // TODO: Ask to use Tor before any network calls go out.
                // TODO: Hit relayUrl to verify creds work

                var success: KeyRestoreResponse.Success? = null
                keyRestore.restoreKeys(
                    privateKey = decryptedCode.privateKey,
                    publicKey = decryptedCode.publicKey,
                    userPin = pin,
                    relayUrl = decryptedCode.relayUrl,
                    authorizationToken = decryptedCode.authorizationToken,
                    transportKey = null
                ).collect { flowResponse ->
                    // TODO: Implement in Authentication View when it get's built/refactored
                    if (flowResponse is KeyRestoreResponse.Success) {
                        success = flowResponse
                    }
                }

                success?.let { _ ->
                    // Overwrite PIN
                    viewState.pinWriter.reset()
                    repeat(6) {
                        viewState.pinWriter.append('0')
                    }

                } ?: updateViewState(
                    OnBoardConnectingViewState.Set2_DecryptKeys(viewState.restoreCode)
                ).also {
                    submitSideEffect(OnBoardConnectingSideEffect.InvalidPin)
                }

            } catch (e: Exception) {
                decryptionJobException = e
            }
        }

        viewModelScope.launch(mainImmediate) {
            decryptionJob?.join()
            decryptionJobException?.let { exception ->
                updateViewState(
                    // reset view state
                    OnBoardConnectingViewState.Set2_DecryptKeys(viewState.restoreCode)
                )
                exception.printStackTrace()
                submitSideEffect(OnBoardConnectingSideEffect.DecryptionFailure)
            }
        }
    }
    override suspend fun onMotionSceneCompletion(value: Any) {
        return
    }

    private fun collectConnectionState() {
        viewModelScope.launch(mainImmediate) {
            connectManagerRepository.connectionManagerState.collect { connectionState ->
                when (connectionState) {
                    is OwnerRegistrationState.MnemonicWords -> {
                        submitSideEffect(OnBoardConnectingSideEffect.ShowMnemonicToUser(connectionState.words) {})
                    }
                    is OwnerRegistrationState.OwnerRegistered -> {
                        connectionState.mixerServerIp?.let { storeNetworkMixerIp(it) }
                        connectionState.defaultTribe?.let { storeDefaultTribe(it) }

                        storeEnvironmentType(connectionState.isProductionEnvironment)

                        val needsToFetchConfig = connectionState.routerUrl.isNullOrEmpty() || connectionState.tirbeServerHost.isNullOrEmpty()

                        if (needsToFetchConfig) {
                            fetchMissingAccountConfig(
                                isProductionEnvironment = connectionState.isProductionEnvironment,
                                routerUrl = connectionState.routerUrl,
                                tribeServerHost = connectionState.tirbeServerHost,
                                defaultTribe = connectionState.defaultTribe,
                                isRestore = connectionState.isRestoreAccount
                            )
                        } else {
                            connectionState.routerUrl?.let { storeRouterUrl(it) }
                            connectionState.tirbeServerHost?.let { storeTribeServerIp(it) }
                            delay(100L)

                            if (connectionState.isRestoreAccount) {
                                navigator.toOnBoardDesktopScreen()
                            } else {
                                navigator.toOnBoardNameScreen()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun collectUserState() {
        viewModelScope.launch(mainImmediate) {
            connectManagerRepository.userStateFlow.collect { connectionState ->
                if (connectionState != null) {
                    storeUserState(connectionState)
                }
            }
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun collectConnectManagerErrorState(){
        viewModelScope.launch(mainImmediate) {
            connectManagerRepository.connectManagerErrorState.collect { connectManagerError ->
                when (connectManagerError) {
                    is ConnectManagerError.GenerateXPubError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_generate_xpub_error))
                        )
                    }
                    is ConnectManagerError.GenerateMnemonicError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_generate_mnemonic_error))
                        )
                    }
                    is ConnectManagerError.ProcessInviteError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_process_invite_error))
                        )
                    }
                    is ConnectManagerError.MqttConnectError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            String.format(app.getString(R_common.string.connect_manager_mqtt_connect_error), connectManagerError.error))
                        )
                    }
                    is ConnectManagerError.SubscribeOwnerError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_subscribe_owner_error))
                        )
                    }
                    is ConnectManagerError.MqttClientError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_mqtt_client_error))
                        )
                    }
                    is ConnectManagerError.MqttInitError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            String.format(
                                app.getString(R_common.string.connect_manager_mqtt_init_error), connectManagerError.logs))
                        )
                    }
                    is ConnectManagerError.FetchMessageError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_fetch_message_error))
                        )
                    }
                    is ConnectManagerError.FetchFirstMessageError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_fetch_first_message_error))
                        )
                    }
                    is ConnectManagerError.ParseInvite -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_parse_invite_error))
                        )
                    }
                    is ConnectManagerError.RestoreConnectionError -> {
                        submitSideEffect(OnBoardConnectingSideEffect.NotifyError(
                            app.getString(R_common.string.connect_manager_restore_connection_error))
                        )
                    }
                    else -> {}
                }

                connectManagerError?.let {
                    keyRestore.clearAll()
                    connectManagerRepository.resetAccount()

                    delay(1500L)
                    navigator.popBackStack()
                }
            }
        }
    }

}