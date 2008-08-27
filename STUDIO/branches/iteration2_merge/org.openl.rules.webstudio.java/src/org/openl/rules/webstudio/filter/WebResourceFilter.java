package org.openl.rules.webstudio.filter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;

/**
 * Servlet filter to load web resources (images, html, etc) from classpath.
 *
 * @author Andrey Naumenko
 */
public class WebResourceFilter implements Filter {
    private FilterConfig filterConfig;

    private static final String WEBRESOURCE_PREFIX = "/webresource";

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
            ServletException {

        try {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String path = httpRequest.getRequestURI();

            if (path != null && path.indexOf(WEBRESOURCE_PREFIX) != -1) {
                path = path.substring(path.indexOf(WEBRESOURCE_PREFIX) + WEBRESOURCE_PREFIX.length());
                InputStream stream = WebResourceFilter.class.getResourceAsStream(path);
                if (stream == null) {
                    stream = new FileInputStream(new File(filterConfig.getServletContext().getRealPath(path)));
                }
                OutputStream out = response.getOutputStream();
                IOUtils.copy(stream, out);
                stream.close();
            } else {
                chain.doFilter(request, response);
            }
        } finally {
        }
    }

    public void destroy() {
    }

    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
    }
}
