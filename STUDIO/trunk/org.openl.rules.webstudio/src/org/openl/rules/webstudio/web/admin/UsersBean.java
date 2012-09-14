package org.openl.rules.webstudio.web.admin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.faces.application.FacesMessage;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ManagedProperty;
import javax.faces.bean.RequestScoped;
import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.faces.validator.ValidatorException;

import javax.validation.constraints.Size;

import org.apache.commons.lang.StringUtils;
import org.hibernate.validator.constraints.NotBlank;

import org.openl.rules.security.DefaultPrivileges;
import org.openl.rules.security.Group;
import org.openl.rules.security.Privilege;
import org.openl.rules.security.SimpleUser;
import org.openl.rules.security.User;
import org.openl.rules.webstudio.service.GroupManagementService;
import org.openl.rules.webstudio.service.UserManagementService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * @author Andrei Astrouski
 */
@ManagedBean
@RequestScoped
public class UsersBean {

    @Size(max=25)
    private String firstName;

    @Size(max=25)
    private String lastName;

    @NotBlank(message="Can not be empty")
    @Size(max=25)
    private String username;

    @NotBlank(message="Can not be empty")
    @Size(max=25)
    private String password;

    @NotBlank(message="Can not be empty")
    private String confirmPassword;

    private List<String> groups;

    @ManagedProperty(value="#{userManagementService}")
    private UserManagementService userManagementService;

    @ManagedProperty(value="#{groupManagementService}")
    private GroupManagementService groupManagementService;

    /**
     * Validation for existed user
     */
    public void validateUsername(FacesContext context, UIComponent toValidate, Object value) {
        User user = null;
        try {
            user = userManagementService.loadUserByUsername((String) value);
        } catch (UsernameNotFoundException e) { }

        if (user != null) {
            throw new ValidatorException(
                    new FacesMessage("User with such name already exists"));
        }
    }

    /**
     * Validation for password confirmation
     */
    public void validateConfirmPassword(FacesContext context, UIComponent toValidate, Object value) {
        String confirmPassword = (String) value;
        if (StringUtils.isNotBlank(password) && StringUtils.isNotBlank(confirmPassword)
                && !confirmPassword.equals(password)) {
            throw new ValidatorException(
                    new FacesMessage("Confirm password does not match the password"));
        }
    }

    public List<User> getUsers() {
        return userManagementService.getAllUsers();
    }

    public void addUser() {
        List<Privilege> resultGroups = new ArrayList<Privilege>();
        for (String groupName : groups) {
            resultGroups.add(
                    groupManagementService.getGroupByName(groupName));
        }
        userManagementService.addUser(
                new SimpleUser(firstName, lastName, username, password, resultGroups));
    }

    public void editUser(User user) {
        username = user.getUsername();
        password = user.getPassword();
        firstName = user.getFirstName();
        lastName = user.getLastName();
        groups = new ArrayList<String>();
        Collection<Privilege> authorities = (Collection<Privilege>) user.getAuthorities();
        for (Privilege authority : authorities) {
            if (authority instanceof Group) {
                groups.add(authority.getName());
            }
        }
    }

    public boolean isOnlyAdmin(Object objUser) {
        String allPrivileges = DefaultPrivileges.PRIVILEGE_ALL.name();
        return ((User) objUser).hasPrivilege(allPrivileges)
                && userManagementService.getUsersByPrivilege(allPrivileges).size() == 1;
    }

    public void deleteUser(String username) {
        userManagementService.deleteUser(username);
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    public List<String> getGroups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups;
    }

    public List<SelectItem> getGroupItems() {
        List<SelectItem> result = new ArrayList<SelectItem>();
        List<Group> groups = groupManagementService.getGroups();
        for (Group group : groups) {
            result.add(new SelectItem(group.getName(), group.getDisplayName()));
        }
        return result;
    }

    public void setUserManagementService(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    public void setGroupManagementService(
            GroupManagementService groupManagementService) {
        this.groupManagementService = groupManagementService;
    }

}
