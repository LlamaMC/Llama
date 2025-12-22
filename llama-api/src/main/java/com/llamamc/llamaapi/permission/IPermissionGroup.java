package com.llamamc.llamaapi.permission;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.concurrent.LlamaFuture;

import java.util.List;

public interface IPermissionGroup {

    LlamaFuture<String> name();
    LlamaFuture<List<IPermission>> permissions();
    LlamaFuture<Component> prefix();
    LlamaFuture<Component> suffix();
    LlamaFuture<Void> name(Component name);
    LlamaFuture<Void> prefix(Component name);
    LlamaFuture<Void> suffix(Component name);

}
