package com.oracle.svm.hosted.prophet.model;

import java.util.Objects;
import java.util.Set;

public class SystemContext {

    private String systemName;

    private Set<Module> modules;

    public String getSystemName() {
        return systemName;
    }

    public void setSystemName(String systemName) {
        this.systemName = systemName;
    }

    public Set<Module> getModules() {
        return modules;
    }

    public void setModules(Set<Module> modules) {
        this.modules = modules;
    }

    @Override
    public String toString() {
        return "SystemContext{" +
                        "systemName='" + systemName + '\'' +
                        ", modules=" + modules +
                        '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        SystemContext that = (SystemContext) o;
        return Objects.equals(systemName, that.systemName) && Objects.equals(modules, that.modules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemName, modules);
    }
}
