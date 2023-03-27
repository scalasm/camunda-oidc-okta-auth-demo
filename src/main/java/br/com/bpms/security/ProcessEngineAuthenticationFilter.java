package br.com.bpms.security;

import br.com.bpms.okta.authentication.AuthUtils;
import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.BpmPlatform;
import org.camunda.bpm.engine.AuthorizationService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngines;
import org.camunda.bpm.engine.identity.Group;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.camunda.bpm.engine.authorization.Authorization;
import org.camunda.bpm.engine.authorization.Groups;
import org.camunda.bpm.engine.authorization.Permissions;
import org.camunda.bpm.engine.authorization.Resource;
import org.camunda.bpm.engine.authorization.Resources;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.camunda.bpm.engine.authorization.Authorization.ANY;
import static org.camunda.bpm.engine.authorization.Authorization.AUTH_TYPE_GRANT;
import static org.camunda.bpm.engine.authorization.Permissions.ACCESS;
import static org.camunda.bpm.engine.authorization.Permissions.ALL;
import static org.camunda.bpm.engine.authorization.Permissions.READ;
import static org.camunda.bpm.engine.authorization.Permissions.UPDATE;
import static org.camunda.bpm.engine.authorization.Resources.APPLICATION;
import static org.camunda.bpm.engine.authorization.Resources.FILTER;
import static org.camunda.bpm.engine.authorization.Resources.TASK;
import static org.camunda.bpm.engine.authorization.Resources.USER;

/**
 * This filter will be involved in dealing with the REST API - Camunda Web apps are authenticated separately.
 */
@Slf4j
public class ProcessEngineAuthenticationFilter implements Filter {

    @Override
    public void doFilter(final ServletRequest req, final ServletResponse res, final FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest request = (HttpServletRequest) req;
        final HttpServletResponse response = (HttpServletResponse) res;

        final ProcessEngine engine = BpmPlatform.getDefaultProcessEngine() != null
                ? BpmPlatform.getDefaultProcessEngine()
                : ProcessEngines.getDefaultProcessEngine(false);

        if (engine == null) {
            final String msg = "Default Process engine not available";
            final ObjectMapper objectMapper = new ObjectMapper();
            response.setStatus(Response.Status.NOT_FOUND.getStatusCode());
            response.setContentType(MediaType.APPLICATION_JSON);
            objectMapper.writer().writeValue(response.getWriter(), msg);
            response.getWriter().flush();
            return;
        }
        try {
            if (request.getUserPrincipal() != null && request.getUserPrincipal().getName() != null) {
                final String userId = request.getUserPrincipal().getName();
                this.setAuthenticatedUser(engine, userId, AuthUtils.getUserGroups((OAuth2User) request.getUserPrincipal(), userId), new ArrayList<>());
                chain.doFilter(req, res);
            } else {
                response.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
            }
        } finally {
            this.clearAuthentication(engine);
        }
    }

    protected void setAuthenticatedUser(final ProcessEngine engine, final String userId,
                                        final List<String> groupIds, final List<String> tenantIds) {
        ensureCamundaGroupsExist(engine, groupIds);
        engine.getIdentityService().setAuthentication(userId, groupIds, tenantIds);
    }

    protected void clearAuthentication(final ProcessEngine engine) {
        engine.getIdentityService().clearAuthentication();
    }

    /**
     * Ensure that in Camunda we have the same groups as in Okta.
     * @param engine the Camunda engine
     * @param groupIds List of group ids from Okta
     */
    private void ensureCamundaGroupsExist(final ProcessEngine engine, final List<String> groupIds) {
        final IdentityService identityService = engine.getIdentityService();

        groupIds.forEach(groupId -> {
            final Group existingGroup = identityService
                .createGroupQuery()
                .groupId(groupId)
                .singleResult();

            if (existingGroup == null) {
                final String groupName = groupId;
                final String groupType = Groups.GROUP_TYPE_SYSTEM;
                final Group newCamundaGroup = createCamundaGroup(groupId, groupName, groupType, engine, identityService);
                log.info("Group {} created in Camunda DB.", newCamundaGroup.getName());
            }
        });
    }

    public Group createCamundaGroup(String id, String name, String type, ProcessEngine engine, IdentityService identityService) {
        final Group newCamundaGroup = identityService.newGroup(id);
        newCamundaGroup.setName(name);
        newCamundaGroup.setType(type);
        
        identityService.saveGroup(newCamundaGroup);
        
        return newCamundaGroup;
    }

}

