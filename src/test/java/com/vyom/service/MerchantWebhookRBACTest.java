package com.vyom.service;

import com.vyom.dto.request.RegisterWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookEndpointRequest;
import com.vyom.dto.request.UpdateWebhookSubscriptionsRequest;
import com.vyom.dto.response.PagedResponse;
import com.vyom.dto.response.WebhookEndpointCreatedResponse;
import com.vyom.dto.response.WebhookEndpointResponse;
import com.vyom.dto.response.WebhookEventSubscriptionResponse;
import com.vyom.entity.Merchant;
import com.vyom.entity.MerchantMember;
import com.vyom.entity.MerchantWebhookEndpoint;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.MembershipStatus;
import com.vyom.exception.InsufficientMerchantPrivilegeException;
import com.vyom.exception.MerchantAccountSuspendedException;
import com.vyom.exception.MerchantMembershipNotFoundException;
import com.vyom.exception.WebhookEndpointNotFoundException;
import com.vyom.repository.MerchantMemberRepository;
import com.vyom.repository.MerchantRepository;
import com.vyom.repository.MerchantWebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Merchant Webhook RBAC & Multi-Tenant Isolation Tests")
class MerchantWebhookRBACTest {

    @Mock
    private MerchantRepository merchantRepository;

    @Mock
    private MerchantMemberRepository memberRepository;

    @Mock
    private MerchantWebhookEndpointRepository endpointRepository;

    @Mock
    private WebhookDeliveryService deliveryService;

    @Mock
    private WebhookCryptoService cryptoService;

    @Mock
    private SSRFShieldService ssrfShieldService;

    private MerchantSecurityService securityService;
    private MerchantWebhookService webhookService;

    private final UUID merchantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID endpointId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final UUID ownerUserId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID adminUserId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private final UUID operatorUserId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private final UUID viewerUserId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private final UUID nonMemberUserId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private Merchant activeMerchant;
    private MerchantWebhookEndpoint sampleEndpoint;

    @BeforeEach
    void setUp() {
        securityService = new MerchantSecurityService(merchantRepository, memberRepository);
        webhookService = new MerchantWebhookService(
                securityService,
                endpointRepository,
                deliveryService,
                cryptoService,
                ssrfShieldService,
                null
        );

        activeMerchant = new Merchant();
        activeMerchant.setId(merchantId);
        activeMerchant.setMerchantCode("MCH_TEST");
        activeMerchant.setAccountStatus(AccountStatus.ACTIVE);

        sampleEndpoint = new MerchantWebhookEndpoint(
                merchantId,
                "https://api.merchant.com/webhooks",
                "Primary Webhook",
                "whsec_ciphertext",
                "whsec_iv",
                "whsec_tag",
                "whsec_...1234",
                1,
                1,
                List.of("order.paid")
        );
        sampleEndpoint.setId(endpointId);
        sampleEndpoint.setIsActive(true);

        lenient().when(endpointRepository.save(any(MerchantWebhookEndpoint.class))).thenAnswer(i -> i.getArgument(0));

        lenient().when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(activeMerchant));

        // Set up active members
        setupMember(ownerUserId, MerchantRole.MERCHANT_OWNER, MembershipStatus.ACTIVE);
        setupMember(adminUserId, MerchantRole.MERCHANT_ADMIN, MembershipStatus.ACTIVE);
        setupMember(operatorUserId, MerchantRole.MERCHANT_OPERATOR, MembershipStatus.ACTIVE);
        setupMember(viewerUserId, MerchantRole.MERCHANT_VIEWER, MembershipStatus.ACTIVE);

        // Non member
        lenient().when(memberRepository.findByMerchantIdAndUserId(merchantId, nonMemberUserId)).thenReturn(Optional.empty());
    }

    private void setupMember(UUID userId, MerchantRole role, MembershipStatus status) {
        MerchantMember member = new MerchantMember();
        member.setId(UUID.randomUUID());
        member.setMerchantId(merchantId);
        member.setUserId(userId);
        member.setMerchantRole(role);
        member.setMembershipStatus(status);
        lenient().when(memberRepository.findByMerchantIdAndUserId(merchantId, userId)).thenReturn(Optional.of(member));
    }

    @Test
    @DisplayName("MERCHANT_OWNER: Full permissions across all webhook operations")
    void owner_hasFullPermissions() {
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest(
                "https://api.merchant.com/webhooks",
                "Owner registered",
                List.of("order.paid")
        );
        when(endpointRepository.existsByMerchantIdAndUrl(eq(merchantId), any())).thenReturn(false);
        when(cryptoService.generateSigningSecret()).thenReturn("whsec_secret");
        when(cryptoService.getActiveKeyVersion()).thenReturn(1);
        when(cryptoService.encryptSecret(any(), eq(merchantId), any(), eq(1)))
                .thenReturn(new WebhookCryptoService.EncryptedSecret("cipher", "iv", "tag", "whsec_...cdef", 1));
        when(endpointRepository.save(any(MerchantWebhookEndpoint.class))).thenAnswer(i -> {
            MerchantWebhookEndpoint e = i.getArgument(0);
            e.setId(endpointId);
            return e;
        });

        WebhookEndpointCreatedResponse created = webhookService.registerEndpoint(merchantId, regReq, ownerUserId);
        assertThat(created).isNotNull();
        assertThat(created.id()).isEqualTo(endpointId);

        // Update
        when(endpointRepository.findByIdAndMerchantId(endpointId, merchantId)).thenReturn(Optional.of(sampleEndpoint));
        UpdateWebhookEndpointRequest updateReq = new UpdateWebhookEndpointRequest("https://new.url/webhooks", "Updated");
        WebhookEndpointResponse updated = webhookService.updateEndpoint(merchantId, endpointId, updateReq, ownerUserId);
        assertThat(updated).isNotNull();

        // Rotate secret
        WebhookEndpointCreatedResponse rotated = webhookService.rotateSecret(merchantId, endpointId, ownerUserId);
        assertThat(rotated).isNotNull();
        assertThat(rotated.secretVersion()).isEqualTo(2);

        // Disable and Enable
        WebhookEndpointResponse disabled = webhookService.disableEndpoint(merchantId, endpointId, ownerUserId);
        assertThat(disabled.isActive()).isFalse();

        WebhookEndpointResponse enabled = webhookService.enableEndpoint(merchantId, endpointId, ownerUserId);
        assertThat(enabled.isActive()).isTrue();

        // Archive
        WebhookEndpointResponse archived = webhookService.archiveEndpoint(merchantId, endpointId, ownerUserId);
        assertThat(archived.archivedAt()).isNotNull();
    }

    @Test
    @DisplayName("MERCHANT_ADMIN: Full administrative permissions equivalent to OWNER for webhooks")
    void admin_hasFullWebhookPermissions() {
        when(endpointRepository.findByIdAndMerchantId(endpointId, merchantId)).thenReturn(Optional.of(sampleEndpoint));

        // Subscriptions
        UpdateWebhookSubscriptionsRequest subReq = new UpdateWebhookSubscriptionsRequest(List.of("order.refunded"));
        WebhookEventSubscriptionResponse subRes = webhookService.updateSubscriptions(merchantId, endpointId, subReq, adminUserId);
        assertThat(subRes.subscribedEvents()).containsExactly("order.refunded");

        // Secret rotation
        when(cryptoService.generateSigningSecret()).thenReturn("whsec_new_secret");
        when(cryptoService.getActiveKeyVersion()).thenReturn(1);
        when(cryptoService.encryptSecret(any(), eq(merchantId), any(), eq(1)))
                .thenReturn(new WebhookCryptoService.EncryptedSecret("cipher2", "iv2", "tag2", "whsec_...cdef", 1));
        WebhookEndpointCreatedResponse rotated = webhookService.rotateSecret(merchantId, endpointId, adminUserId);
        assertThat(rotated.secretVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("MERCHANT_OPERATOR: Allowed to view, enable and disable; forbidden from register, update, archive, rotate")
    void operator_permittedOperationsAndRestrictions() {
        when(endpointRepository.findByIdAndMerchantId(endpointId, merchantId)).thenReturn(Optional.of(sampleEndpoint));

        // Allowed: View endpoint
        WebhookEndpointResponse view = webhookService.getEndpoint(merchantId, endpointId, operatorUserId);
        assertThat(view).isNotNull();

        // Allowed: Disable
        WebhookEndpointResponse disabled = webhookService.disableEndpoint(merchantId, endpointId, operatorUserId);
        assertThat(disabled.isActive()).isFalse();

        // Allowed: Enable
        WebhookEndpointResponse enabled = webhookService.enableEndpoint(merchantId, endpointId, operatorUserId);
        assertThat(enabled.isActive()).isTrue();

        // Forbidden: Register
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest("https://api.test/wh", "Op", List.of("order.paid"));
        assertThatThrownBy(() -> webhookService.registerEndpoint(merchantId, regReq, operatorUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Update
        UpdateWebhookEndpointRequest updateReq = new UpdateWebhookEndpointRequest("https://new.test/wh", "Op");
        assertThatThrownBy(() -> webhookService.updateEndpoint(merchantId, endpointId, updateReq, operatorUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Archive
        assertThatThrownBy(() -> webhookService.archiveEndpoint(merchantId, endpointId, operatorUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Rotate secret
        assertThatThrownBy(() -> webhookService.rotateSecret(merchantId, endpointId, operatorUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Modify subscriptions
        UpdateWebhookSubscriptionsRequest subReq = new UpdateWebhookSubscriptionsRequest(List.of("order.paid"));
        assertThatThrownBy(() -> webhookService.updateSubscriptions(merchantId, endpointId, subReq, operatorUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);
    }

    @Test
    @DisplayName("MERCHANT_VIEWER: Read-only access allowed; mutation operations strictly forbidden")
    void viewer_readOnlyAccessAndRestrictions() {
        when(endpointRepository.findByIdAndMerchantId(endpointId, merchantId)).thenReturn(Optional.of(sampleEndpoint));
        when(endpointRepository.findByMerchantId(eq(merchantId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleEndpoint)));

        // Allowed: List
        PagedResponse<WebhookEndpointResponse> list = webhookService.listEndpoints(merchantId, PageRequest.of(0, 10), viewerUserId);
        assertThat(list.items()).hasSize(1);

        // Allowed: Get
        WebhookEndpointResponse endpoint = webhookService.getEndpoint(merchantId, endpointId, viewerUserId);
        assertThat(endpoint.id()).isEqualTo(endpointId);

        // Allowed: Get Subscriptions
        WebhookEventSubscriptionResponse subs = webhookService.getSubscriptions(merchantId, endpointId, viewerUserId);
        assertThat(subs.subscribedEvents()).contains("order.paid");

        // Forbidden: Disable
        assertThatThrownBy(() -> webhookService.disableEndpoint(merchantId, endpointId, viewerUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Enable
        assertThatThrownBy(() -> webhookService.enableEndpoint(merchantId, endpointId, viewerUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Register
        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest("https://api.test/wh", "Viewer", List.of("order.paid"));
        assertThatThrownBy(() -> webhookService.registerEndpoint(merchantId, regReq, viewerUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Archive
        assertThatThrownBy(() -> webhookService.archiveEndpoint(merchantId, endpointId, viewerUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);

        // Forbidden: Rotate Secret
        assertThatThrownBy(() -> webhookService.rotateSecret(merchantId, endpointId, viewerUserId))
                .isInstanceOf(InsufficientMerchantPrivilegeException.class);
    }

    @Test
    @DisplayName("Non-member: Any access attempt is rejected with MerchantMembershipNotFoundException")
    void nonMember_rejected() {
        assertThatThrownBy(() -> webhookService.getEndpoint(merchantId, endpointId, nonMemberUserId))
                .isInstanceOf(MerchantMembershipNotFoundException.class);

        verify(endpointRepository, never()).findByIdAndMerchantId(any(), any());
    }

    @Test
    @DisplayName("IDOR & Tenant Isolation: Accessing endpoint of another merchant returns 404 (zero leakage)")
    void crossMerchantAccess_returns404() {
        // When queried with caller's merchantId, repository returns empty Optional
        when(endpointRepository.findByIdAndMerchantId(endpointId, merchantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.getEndpoint(merchantId, endpointId, ownerUserId))
                .isInstanceOf(WebhookEndpointNotFoundException.class)
                .hasMessageContaining(endpointId.toString());

        // Verify that updateEndpoint also returns 404 on cross-merchant lookup
        UpdateWebhookEndpointRequest updateReq = new UpdateWebhookEndpointRequest("https://hacked.com", "Hacked");
        assertThatThrownBy(() -> webhookService.updateEndpoint(merchantId, endpointId, updateReq, ownerUserId))
                .isInstanceOf(WebhookEndpointNotFoundException.class);
    }

    @Test
    @DisplayName("Suspended Merchant: Mutation operations blocked with MerchantAccountSuspendedException")
    void suspendedMerchant_operationsBlocked() {
        activeMerchant.setAccountStatus(AccountStatus.SUSPENDED);

        UpdateWebhookEndpointRequest updateReq = new UpdateWebhookEndpointRequest("https://new.test/wh", "Updated");
        assertThatThrownBy(() -> webhookService.updateEndpoint(merchantId, endpointId, updateReq, ownerUserId))
                .isInstanceOf(MerchantAccountSuspendedException.class);

        RegisterWebhookEndpointRequest regReq = new RegisterWebhookEndpointRequest("https://api.test/wh", "Suspended", List.of("order.paid"));
        assertThatThrownBy(() -> webhookService.registerEndpoint(merchantId, regReq, ownerUserId))
                .isInstanceOf(MerchantAccountSuspendedException.class);
    }
}
