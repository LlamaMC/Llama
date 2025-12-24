package com.llamamc.llama.config;

import com.llamamc.llamaapi.config.Config;
import com.llamamc.llamaapi.config.Key;

import java.util.List;
import java.util.Map;

@Config(name = "permissions")
public class PermissionsConfig {

    @Key("users")
    private Map<String, Map<String, List<String>>> users;
    @Key("groups")
    private Map<String, List<String>> groups;

    public void setUsers(Map<String, Map<String, List<String>>> users) {
        this.users = users;
    }

    public void setGroups(Map<String, List<String>> groups) {
        this.groups = groups;
    }

    public Map<String, Map<String, List<String>>> getUsers() {
        return users;
    }

    public Map<String, List<String>> getGroups() {
        return groups;
    }
}
