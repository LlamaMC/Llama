package com.llamamc.llamaapi.config.type;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Map;

public interface ITypeProcessor {
    boolean supports(String fileName);
    Map<String, Object> parse(InputStream inputStream) throws Exception;
    void write(Writer writer, Map<String, Object> data) throws IOException;
}
