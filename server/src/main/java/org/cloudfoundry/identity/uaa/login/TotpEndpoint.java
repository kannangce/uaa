package org.cloudfoundry.identity.uaa.login;

import com.google.zxing.WriterException;
import com.warrenstrange.googleauth.GoogleAuthenticatorException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.authentication.event.UserAuthenticationSuccessEvent;
import org.cloudfoundry.identity.uaa.mfa.GoogleAuthenticatorAdapter;
import org.cloudfoundry.identity.uaa.mfa.MfaProvider;
import org.cloudfoundry.identity.uaa.mfa.MfaProviderProvisioning;
import org.cloudfoundry.identity.uaa.mfa.UserGoogleMfaCredentialsProvisioning;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Controller
public class TotpEndpoint implements ApplicationEventPublisherAware {
    public static final String MFA_VALIDATE_AUTH = "MFA_VALIDATE_AUTH";
    public static final String MFA_VALIDATE_USER = "MFA_VALIDATE_USER";

    private UserGoogleMfaCredentialsProvisioning userGoogleMfaCredentialsProvisioning;
    private MfaProviderProvisioning mfaProviderProvisioning;
    private Log logger = LogFactory.getLog(TotpEndpoint.class);

    private GoogleAuthenticatorAdapter googleAuthenticatorService;

    private ApplicationEventPublisher publisher;

    private SavedRequestAwareAuthenticationSuccessHandler redirectingHandler;


    private void publish(ApplicationEvent event) {
        if (publisher != null) {
            publisher.publishEvent(event);
        }
    }

    @RequestMapping(value = {"/login/mfa/register"}, method = RequestMethod.GET)
    public String generateQrUrl(HttpSession session, Model model) throws NoSuchAlgorithmException, WriterException, IOException, UaaPrincipalIsNotInSession {

        UaaPrincipal uaaPrincipal = getSessionAuthPrincipal(session);

        String providerName = IdentityZoneHolder.get().getConfig().getMfaConfig().getProviderName();
        MfaProvider provider = mfaProviderProvisioning.retrieveByName(providerName, IdentityZoneHolder.get().getId());

        if(userGoogleMfaCredentialsProvisioning.activeUserCredentialExists(uaaPrincipal.getId(), provider.getId())) {
            return "redirect:/login/mfa/verify";
        } else {
            String url = googleAuthenticatorService.getOtpAuthURL(provider.getConfig().getIssuer(), uaaPrincipal.getId(), uaaPrincipal.getName());
            model.addAttribute("qrurl", url);
            model.addAttribute("identity_zone", IdentityZoneHolder.get().getName());

            return "mfa/qr_code";
        }
    }

    @RequestMapping(value = {"/login/mfa/verify"}, method = RequestMethod.GET)
    public ModelAndView totpAuthorize(HttpSession session, Model model) throws UaaPrincipalIsNotInSession {
        UaaPrincipal uaaPrincipal = getSessionAuthPrincipal(session);
        return renderEnterCodePage(model, uaaPrincipal);

    }

    @RequestMapping(value = {"/login/mfa/verify.do"}, method = RequestMethod.POST)
    public ModelAndView validateCode(Model model,
                               HttpSession session,
                               HttpServletRequest request, HttpServletResponse response,
                               @RequestParam("code") String code)
            throws NoSuchAlgorithmException, IOException, UaaPrincipalIsNotInSession {
        UaaAuthentication sessionAuth = session.getAttribute(MFA_VALIDATE_AUTH) instanceof UaaAuthentication ? (UaaAuthentication) session.getAttribute(MFA_VALIDATE_AUTH) : null;
        UaaPrincipal uaaPrincipal = getSessionAuthPrincipal(session);

        try {
            Integer codeValue = Integer.valueOf(code);
            if(googleAuthenticatorService.isValidCode(uaaPrincipal.getId(), codeValue)) {
                userGoogleMfaCredentialsProvisioning.persistCredentials();
                session.removeAttribute(MFA_VALIDATE_AUTH);
                session.removeAttribute(MFA_VALIDATE_USER);
                Set<String> authMethods = new HashSet<>(sessionAuth.getAuthenticationMethods());
                authMethods.addAll(Arrays.asList("otp", "mfa"));
                sessionAuth.setAuthenticationMethods(authMethods);
                SecurityContextHolder.getContext().setAuthentication(sessionAuth);
                redirectingHandler.onAuthenticationSuccess(request, response, sessionAuth);

                UaaUser uaaUser = getSessionUser(session);
                if(uaaUser != null) {
                    publish(new UserAuthenticationSuccessEvent(uaaUser,sessionAuth));
                }
                return new ModelAndView("home", Collections.emptyMap());
            }
            logger.debug("Code authorization failed for user: " + uaaPrincipal.getId());
            model.addAttribute("error", "Incorrect code, please try again.");
        } catch (NumberFormatException|GoogleAuthenticatorException e) {
            logger.debug("Error validating the code for user: " + uaaPrincipal.getId() + ". Error: " + e.getMessage());
            model.addAttribute("error", "Incorrect code, please try again.");
        } catch (ServletException e) {
            logger.debug("Error redirecting user: " + uaaPrincipal.getId() + ". Error: " + e.getMessage());
            model.addAttribute("error", "Can't redirect user");
        }
        return renderEnterCodePage(model, uaaPrincipal);
    }

    private UaaUser getSessionUser(HttpSession session) {
        UaaUser res = session.getAttribute(MFA_VALIDATE_USER) instanceof UaaUser ? (UaaUser) session.getAttribute(MFA_VALIDATE_USER) : null;
        if(res == null) {
            logger.error("UAA User not found in session during mfa verify.");
        }
        return res;
    }

    public void setUserGoogleMfaCredentialsProvisioning(UserGoogleMfaCredentialsProvisioning userGoogleMfaCredentialsProvisioning) {
        this.userGoogleMfaCredentialsProvisioning = userGoogleMfaCredentialsProvisioning;
    }

    public void setMfaProviderProvisioning(MfaProviderProvisioning mfaProviderProvisioning) {
        this.mfaProviderProvisioning = mfaProviderProvisioning;
    }

    public void setGoogleAuthenticatorService(GoogleAuthenticatorAdapter googleAuthenticatorService) {
        this.googleAuthenticatorService = googleAuthenticatorService;
    }

    @ExceptionHandler(UaaPrincipalIsNotInSession.class)
    public ModelAndView handleUaaPrincipalIsNotInSession() {
        return new ModelAndView("redirect:/login", Collections.emptyMap());
    }

    private ModelAndView renderEnterCodePage(Model model, UaaPrincipal uaaPrincipal) {
        model.addAttribute("is_first_time_user", userGoogleMfaCredentialsProvisioning.isFirstTimeMFAUser(uaaPrincipal));
        model.addAttribute("identity_zone", IdentityZoneHolder.get().getName());
        return new ModelAndView("mfa/enter_code", model.asMap());
    }

    private UaaPrincipal getSessionAuthPrincipal(HttpSession session) throws UaaPrincipalIsNotInSession {
        UaaAuthentication sessionAuth = session.getAttribute(MFA_VALIDATE_AUTH) instanceof UaaAuthentication ? (UaaAuthentication) session.getAttribute(MFA_VALIDATE_AUTH) : null;
        if(sessionAuth != null) {
            UaaPrincipal principal = sessionAuth.getPrincipal();
            if(principal != null) {
                return principal;
            }
        }

        throw new UaaPrincipalIsNotInSession();
    }

    public void setRedirectingHandler(SavedRequestAwareAuthenticationSuccessHandler handler) {
        this.redirectingHandler = handler;
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.publisher = applicationEventPublisher;
    }

    public class UaaPrincipalIsNotInSession extends Exception {}
}
