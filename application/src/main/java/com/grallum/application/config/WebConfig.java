package com.grallum.application.config;

import com.grallum.application.exception.*;
import com.grallum.application.model.PageQuery;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/** Binds shared page queries and prevents credential-bearing responses from being cached. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  /** Adds the bounded page resolver before MVC's implicit model-attribute binding. */
  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new PageResolver());
  }

  /** Adds no-store headers to dynamic responses, including explicit token exchanges. */
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new NoStoreInterceptor()).excludePathPatterns("/assets/**");
  }

  /** Resolves the public n/last query contract to a domain-neutral bounded page request. */
  private static final class PageResolver implements HandlerMethodArgumentResolver {
    /** Recognizes only the shared PageQuery value type. */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return parameter.getParameterType() == PageQuery.class;
    }

    /**
     * Parses a unique page size and cursor; invalid or duplicate parameters fail before service
     * execution.
     */
    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer container,
        NativeWebRequest request,
        WebDataBinderFactory factory) {
      var sizes = request.getParameterValues("n");
      var cursors = request.getParameterValues("last");
      if (sizes != null && sizes.length != 1 || cursors != null && cursors.length != 1)
        throw new BusinessException(BusinessErrorCode.INVALID_INPUT, "Duplicate page parameter");
      return new PageQuery(
          sizes == null ? 100 : Integer.parseInt(sizes[0]),
          cursors == null ? Optional.empty() : Optional.of(cursors[0]));
    }
  }

  /**
   * Marks authenticated and credential responses as non-cacheable without retaining request data.
   */
  private static final class NoStoreInterceptor implements HandlerInterceptor {
    /** Sets no-store before rendering a view or serializing a dynamic response. */
    @Override
    public boolean preHandle(
        HttpServletRequest request, HttpServletResponse response, Object handler) {
      response.setHeader("Cache-Control", "no-store");
      return true;
    }
  }
}
