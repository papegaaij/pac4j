package org.pac4j.core.engine;

import org.pac4j.core.authorization.checker.AuthorizationChecker;
import org.pac4j.core.authorization.checker.DefaultAuthorizationChecker;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.client.DirectClient;
import org.pac4j.core.client.IndirectClient;
import org.pac4j.core.client.direct.AnonymousClient;
import org.pac4j.core.client.finder.ClientFinder;
import org.pac4j.core.client.finder.DefaultClientFinder;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.Pac4jConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.HttpAction;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.http.HttpActionAdapter;
import org.pac4j.core.matching.DefaultMatchingChecker;
import org.pac4j.core.matching.MatchingChecker;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.ProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.pac4j.core.util.CommonHelper.*;

/**
 * <p>Default security logic:</p>
 *
 * <p>If the request matches the <code>matchers</code> configuration (or no <code>matchers</code> are defined), the security is applied.
 * Otherwise, the user is automatically granted access.</p>
 *
 * <p>First, if the user is not authenticated (no profile) and if some clients have been defined in the <code>clients</code> parameter,
 * a login is tried for the direct clients.</p>
 *
 * <p>Then, if the user has profile, authorizations are checked according to the <code>authorizers</code> configuration.
 * If the authorizations are valid, the user is granted access. Otherwise, a 403 error page is displayed.</p>
 *
 * <p>Finally, if the user is still not authenticated (no profile), he is redirected to the appropriate identity provider
 * if the first defined client is an indirect one in the <code>clients</code> configuration. Otherwise, a 401 error page is displayed.</p>
 *
 * @author Jerome Leleu
 * @since 1.9.0
 */
public class DefaultSecurityLogic<R extends Object> implements SecurityLogic<R> {

    protected Logger logger = LoggerFactory.getLogger(getClass());

    private ClientFinder clientFinder = new DefaultClientFinder();

    private AuthorizationChecker authorizationChecker = new DefaultAuthorizationChecker();

    private MatchingChecker matchingChecker = new DefaultMatchingChecker();

    @Override
    public R perform(final WebContext context, final Config config, final SuccessAdapter<R> successAdapter, final HttpActionAdapter<R> httpActionAdapter,
                     final String clients, final String authorizers, final String matchers, final Boolean inputMultiProfile, final Object... parameters) {

        logger.debug("Applying security");

        // default value
        final boolean multiProfile;
        if (inputMultiProfile == null) {
            multiProfile = false;
        } else {
            multiProfile = inputMultiProfile;
        }

        // checks
        assertNotNull("context", context);
        assertNotNull("config", config);
        assertNotNull("httpActionAdapter", httpActionAdapter);
        assertNotNull("clientFinder", clientFinder);
        assertNotNull("authorizationChecker", authorizationChecker);
        assertNotNull("matchingChecker", matchingChecker);
        final Clients configClients = config.getClients();
        assertNotNull("configClients", configClients);

        // logic
        HttpAction action = null;
        try {

            logger.debug("url: {}", context.getFullRequestURL());
            logger.debug("matchers: {}", matchers);
            if (matchingChecker.matches(context, matchers, config.getMatchers())) {

                logger.debug("clients: {}", clients);
                final List<Client> currentClients = clientFinder.find(configClients, context, clients);
                logger.debug("currentClients: {}", currentClients);

                final boolean loadProfilesFromSession = loadProfilesFromSession(context, currentClients);
                logger.debug("loadProfilesFromSession: {}", loadProfilesFromSession);
                final ProfileManager manager = new ProfileManager(context);
                List<CommonProfile> profiles = manager.getAll(loadProfilesFromSession);
                logger.debug("profiles: {}", profiles);

                // no profile and some current clients
                if (isEmpty(profiles) && isNotEmpty(currentClients)) {
                    boolean updated = false;
                    // loop on all clients searching direct ones to perform authentication
                    for (final Client currentClient : currentClients) {
                        if (currentClient instanceof DirectClient) {
                            logger.debug("Performing authentication for direct client: {}", currentClient);

                            final Credentials credentials = currentClient.getCredentials(context);
                            logger.debug("credentials: {}", credentials);
                            final CommonProfile profile = currentClient.getUserProfile(credentials, context);
                            logger.debug("profile: {}", profile);
                            if (profile != null) {
                                final boolean saveProfileInSession = saveProfileInSession(context, currentClients, (DirectClient) currentClient, profile);
                                logger.debug("saveProfileInSession: {} / multiProfile: {}", saveProfileInSession, multiProfile);
                                manager.save(saveProfileInSession, profile, multiProfile);
                                updated = true;
                                if (!multiProfile) {
                                    break;
                                }
                            }
                        }
                    }
                    if (updated) {
                        profiles = manager.getAll(loadProfilesFromSession);
                        logger.debug("new profiles: {}", profiles);
                    }
                }

                // we have profile(s) -> check authorizations
                if (isNotEmpty(profiles)) {
                    logger.debug("authorizers: {}", authorizers);
                    if (authorizationChecker.isAuthorized(context, profiles, authorizers, config.getAuthorizers())) {
                        logger.debug("authenticated and authorized -> grant access");
                        return successAdapter.adapt(context, parameters);
                    } else {
                        logger.debug("forbidden");
                        action = forbidden(context, currentClients, profiles, authorizers);
                    }
                } else {
                    if (startAuthentication(context, currentClients)) {
                        logger.debug("Starting authentication");
                        saveRequestedUrl(context, currentClients);
                        redirectToIdentityProvider(context, currentClients);
                    } else {
                        logger.debug("unauthorized");
                        action = unauthorized(context, currentClients);
                    }
                }

            } else {

                logger.debug("no matching for this request -> grant access");
                return successAdapter.adapt(context, parameters);
            }

        } catch (final HttpAction e) {
            logger.debug("extra HTTP action required in security: {}", e.getCode());
            action = e;
        } catch (final TechnicalException e) {
            throw e;
        } catch (final Exception e) {
            throw new TechnicalException(e);
        }

        if (action == null) {
            return null;
        } else {
            return httpActionAdapter.adapt(action.getCode(), context);
        }
    }

    protected boolean loadProfilesFromSession(final WebContext context, final List<Client> currentClients) {
        return isEmpty(currentClients) || currentClients.get(0) instanceof IndirectClient || currentClients.get(0) instanceof AnonymousClient;
    }

    protected boolean saveProfileInSession(final WebContext context, final List<Client> currentClients, final DirectClient directClient, final CommonProfile profile) {
        return false;
    }

    protected HttpAction forbidden(final WebContext context, final List<Client> currentClients, final List<CommonProfile> profile, final String authorizers) {
        return HttpAction.forbidden("forbidden", context);
    }

    protected boolean startAuthentication(final WebContext context, final List<Client> currentClients) {
        return isNotEmpty(currentClients) && currentClients.get(0) instanceof IndirectClient;
    }

    protected void saveRequestedUrl(final WebContext context, final List<Client> currentClients) throws HttpAction {
        final String requestedUrl = context.getFullRequestURL();
        logger.debug("requestedUrl: {}", requestedUrl);
        context.setSessionAttribute(Pac4jConstants.REQUESTED_URL, requestedUrl);
    }

    protected void redirectToIdentityProvider(final WebContext context, final List<Client> currentClients) throws HttpAction {
        final IndirectClient currentClient = (IndirectClient) currentClients.get(0);
        currentClient.redirect(context);
    }

    protected HttpAction unauthorized(final WebContext context, final List<Client> currentClients) {
        return HttpAction.unauthorized("unauthorized", context, null);
    }

    public ClientFinder getClientFinder() {
        return clientFinder;
    }

    public void setClientFinder(ClientFinder clientFinder) {
        this.clientFinder = clientFinder;
    }

    public AuthorizationChecker getAuthorizationChecker() {
        return authorizationChecker;
    }

    public void setAuthorizationChecker(AuthorizationChecker authorizationChecker) {
        this.authorizationChecker = authorizationChecker;
    }

    public MatchingChecker getMatchingChecker() {
        return matchingChecker;
    }

    public void setMatchingChecker(MatchingChecker matchingChecker) {
        this.matchingChecker = matchingChecker;
    }
}
