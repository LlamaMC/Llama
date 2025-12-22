package com.llamamc.llamaapi.item;

import com.llamamc.llamaapi.component.Component;

public interface IItem {

    Component name();
    Component[] lore();
    Material material();

}
