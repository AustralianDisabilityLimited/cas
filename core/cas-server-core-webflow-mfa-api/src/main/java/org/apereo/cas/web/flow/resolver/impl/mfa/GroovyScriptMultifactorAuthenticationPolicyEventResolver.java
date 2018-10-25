package org.apereo.cas.web.flow.resolver.impl.mfa;

import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.AuthenticationServiceSelectionPlan;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.MultifactorAuthenticationProviderSelector;
import org.apereo.cas.authentication.MultifactorAuthenticationTrigger;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.ticket.registry.TicketRegistrySupport;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.web.flow.authentication.BaseMultifactorAuthenticationProviderEventResolver;
import org.apereo.cas.web.support.WebUtils;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.web.util.CookieGenerator;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import java.util.Optional;
import java.util.Set;

/**
 * This is {@link GroovyScriptMultifactorAuthenticationPolicyEventResolver}
 * that conditionally evaluates a groovy script to resolve the mfa provider id
 * and event.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@Slf4j
@Getter
@Setter
public class GroovyScriptMultifactorAuthenticationPolicyEventResolver extends BaseMultifactorAuthenticationProviderEventResolver {
    private final MultifactorAuthenticationTrigger multifactorAuthenticationTrigger;

    public GroovyScriptMultifactorAuthenticationPolicyEventResolver(final AuthenticationSystemSupport authenticationSystemSupport,
                                                                    final CentralAuthenticationService centralAuthenticationService,
                                                                    final ServicesManager servicesManager,
                                                                    final TicketRegistrySupport ticketRegistrySupport,
                                                                    final CookieGenerator warnCookieGenerator,
                                                                    final AuthenticationServiceSelectionPlan authenticationSelectionStrategies,
                                                                    final MultifactorAuthenticationProviderSelector selector,
                                                                    final MultifactorAuthenticationTrigger multifactorAuthenticationTrigger) {
        super(authenticationSystemSupport, centralAuthenticationService, servicesManager,
            ticketRegistrySupport, warnCookieGenerator,
            authenticationSelectionStrategies, selector);
        this.multifactorAuthenticationTrigger = multifactorAuthenticationTrigger;
    }

    @Override
    public Set<Event> resolveInternal(final RequestContext context) {
        val registeredService = resolveRegisteredServiceInRequestContext(context);
        val authentication = WebUtils.getAuthentication(context);
        val request = WebUtils.getHttpServletRequestFromExternalWebflowContext(context);
        val service = resolveServiceFromAuthenticationRequest(context);

        val result = multifactorAuthenticationTrigger.isActivated(authentication, registeredService, request, service);
        return result.map(provider -> {
            LOGGER.debug("Attempting to build an event based on the authentication provider [{}] and service [{}]", provider, registeredService.getName());
            val event = validateEventIdForMatchingTransitionInContext(provider.getId(), Optional.of(context),
                buildEventAttributeMap(authentication.getPrincipal(), Optional.of(registeredService), provider));
            return CollectionUtils.wrapSet(event);
        }).orElse(null);
    }
}
