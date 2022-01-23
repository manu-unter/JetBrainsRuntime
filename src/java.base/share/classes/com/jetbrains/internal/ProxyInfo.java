/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jetbrains.internal;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

import static java.lang.invoke.MethodHandles.Lookup;

/**
 * Proxy info, like {@link RegisteredProxyInfo}, but with all classes and lookup contexts resolved.
 * Contains all necessary information to create a {@linkplain Proxy proxy}.
 */
class ProxyInfo {

    final Lookup apiModule;
    final Type type;
    final Lookup interFaceLookup;
    final Class<?> interFace;
    final Lookup target;
    final Map<String, StaticMethodMapping> staticMethods = new HashMap<>();

    private ProxyInfo(RegisteredProxyInfo i) {
        this.apiModule = i.apiModule();
        type = i.type();
        interFaceLookup = lookup(getInterfaceLookup(), i.interfaceName());
        interFace = interFaceLookup == null ? null : interFaceLookup.lookupClass();
        target = i.target() == null ? null : lookup(getTargetLookup(), i.target());
        for (RegisteredProxyInfo.StaticMethodMapping m : i.staticMethods()) {
            Lookup l = lookup(getTargetLookup(), m.clazz());
            if (l != null) {
                staticMethods.put(m.interfaceMethodName(), new StaticMethodMapping(l, m.methodName()));
            }
        }
    }

    /**
     * Resolve all classes and lookups for given {@link RegisteredProxyInfo}.
     */
    static ProxyInfo resolve(RegisteredProxyInfo i) {
        ProxyInfo info = new ProxyInfo(i);
        if (info.interFace == null || (info.target == null && info.staticMethods.isEmpty())) return null;
        if (!info.interFace.isInterface()) {
            if (info.type == Type.CLIENT_PROXY) {
                throw new RuntimeException("Tried to create client proxy for non-interface: " + info.interFace);
            } else {
                return null;
            }
        }
        return info;
    }

    Lookup getInterfaceLookup() {
        return type == Type.CLIENT_PROXY ? apiModule : JBRApi.outerLookup;
    }

    Lookup getTargetLookup() {
        return type == Type.CLIENT_PROXY ? JBRApi.outerLookup : apiModule;
    }

    private Lookup lookup(Lookup lookup, String clazz) {
        try {
            return MethodHandles.privateLookupIn(lookup.findClass(clazz), lookup);
        } catch (ClassNotFoundException | IllegalAccessException e) {
            if (lookup == JBRApi.outerLookup) return null;
            else throw new RuntimeException(e);
        }
    }

    record StaticMethodMapping(Lookup lookup, String methodName) {}

    /**
     * Proxy type, see {@link Proxy}
     */
    enum Type {
        PROXY,
        SERVICE,
        CLIENT_PROXY
    }
}
