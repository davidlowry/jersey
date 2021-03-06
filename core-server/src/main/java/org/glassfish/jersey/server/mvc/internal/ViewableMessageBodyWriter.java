/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.jersey.server.mvc.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import javax.ws.rs.ConstrainedTo;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.RuntimeType;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import javax.inject.Inject;

import org.glassfish.jersey.internal.ProcessingException;
import org.glassfish.jersey.internal.inject.Providers;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.server.internal.LocalizationMessages;
import org.glassfish.jersey.server.mvc.Template;
import org.glassfish.jersey.server.mvc.Viewable;
import org.glassfish.jersey.server.mvc.spi.ResolvedViewable;
import org.glassfish.jersey.server.mvc.spi.TemplateProcessor;
import org.glassfish.jersey.server.mvc.spi.ViewableContext;
import org.glassfish.jersey.server.mvc.spi.ViewableContextException;

import org.glassfish.hk2.api.ServiceLocator;

import com.google.common.collect.Sets;

/**
 * {@link MessageBodyWriter Message body writer} for {@link Viewable viewable} entities.
 *
 * @author Paul Sandoz (paul.sandoz at oracle.com)
 * @author Michal Gajdos (michal.gajdos at oracle.com)
 */
@Provider
@ConstrainedTo(RuntimeType.SERVER)
final class ViewableMessageBodyWriter implements MessageBodyWriter<Viewable> {

    @Inject
    private ServiceLocator serviceLocator;

    @Context
    private javax.inject.Provider<ExtendedUriInfo> extendedUriInfoProvider;
    @Context
    private javax.inject.Provider<ContainerRequest> requestProvider;
    @Context
    private javax.inject.Provider<ResourceInfo> resourceInfoProvider;

    @Override
    public boolean isWriteable(final Class<?> type, final Type genericType, final Annotation[] annotations,
                               final MediaType mediaType) {
        return Viewable.class.isAssignableFrom(type);
    }

    @Override
    public long getSize(final Viewable viewable, final Class<?> type, final Type genericType,
                        final Annotation[] annotations, final MediaType mediaType) {
        return -1;
    }

    @Override
    public void writeTo(final Viewable viewable,
                        final Class<?> type,
                        final Type genericType,
                        final Annotation[] annotations,
                        final MediaType mediaType,
                        final MultivaluedMap<String, Object> httpHeaders,
                        final OutputStream entityStream) throws IOException, WebApplicationException {
        final Template template = TemplateHelper.getTemplateAnnotation(annotations);

        try {
            final ResolvedViewable resolvedViewable = resolve(viewable, template);
            if (resolvedViewable == null) {
                throw new WebApplicationException(
                        new ProcessingException(LocalizationMessages.TEMPLATE_NAME_COULD_NOT_BE_RESOLVED(viewable.getTemplateName())),
                        Response.Status.NOT_FOUND);
            }

            httpHeaders.putSingle(HttpHeaders.CONTENT_TYPE, resolvedViewable.getMediaType());
            resolvedViewable.writeTo(entityStream);
        } catch (ViewableContextException vce) {
            throw new NotFoundException(vce);
        }
    }

    /**
     * Resolve the given {@link Viewable viewable} using {@link ViewableContext}.
     *
     * @param viewable viewable to be resolved.
     * @param template resource method/class annotation.
     * @return resolved viewable or {@code null}, if the viewable cannot be resolved.
     */
    private ResolvedViewable resolve(final Viewable viewable, final Template template) {
        if (viewable instanceof ResolvedViewable) {
            return (ResolvedViewable) viewable;
        } else {
            final ViewableContext viewableContext = getViewableContext();
            final Set<TemplateProcessor> templateProcessors = getTemplateProcessors();

            final List<MediaType> producibleMediaTypes = TemplateHelper
                    .getProducibleMediaTypes(requestProvider.get(), extendedUriInfoProvider.get(), null);

            if (viewable instanceof ImplicitViewable) {
                // Template Names.
                final ImplicitViewable implicitViewable = (ImplicitViewable) viewable;

                for (final String templateName : implicitViewable.getTemplateNames()) {
                    final Viewable simpleViewable = new Viewable(templateName, viewable.getModel(), viewable.getResolvingClass());

                    final ResolvedViewable resolvedViewable = resolve(simpleViewable, producibleMediaTypes,
                            simpleViewable.getResolvingClass(), viewableContext, templateProcessors);

                    if (resolvedViewable != null) {
                        return resolvedViewable;
                    }
                }
            } else {
                return resolve(viewable, producibleMediaTypes, getResolvingClass(viewable, template, resourceInfoProvider.get()),
                        viewableContext, templateProcessors);
            }

            return null;
        }
    }

    /**
     * Resolve given {@link Viewable viewable} for a list of {@link MediaType mediaTypes} and a {@link Class resolvingClass}
     * using given {@link ViewableContext viewableContext} and a set of {@link TemplateProcessor templateProcessors}
     *
     * @param viewable viewable to be resolved.
     * @param mediaTypes producible media types.
     * @param resolvingClass non-null resolving class.
     * @param viewableContext viewable context.
     * @param templateProcessors collection of available template processors.
     * @return resolved viewable or {@code null}, if the viewable cannot be resolved.
     */
    private ResolvedViewable resolve(final Viewable viewable, final List<MediaType> mediaTypes, final Class<?> resolvingClass,
                                     final ViewableContext viewableContext, final Set<TemplateProcessor> templateProcessors) {
        for (TemplateProcessor templateProcessor : templateProcessors) {
            for (final MediaType mediaType : mediaTypes) {
                final ResolvedViewable resolvedViewable = viewableContext
                        .resolveViewable(viewable, mediaType, resolvingClass, templateProcessor);

                if (resolvedViewable != null) {
                    return resolvedViewable;
                }
            }
        }

        return null;
    }

    /**
     * Get a {@link java.util.LinkedHashSet collection} of available template processors.
     *
     * @return set of template processors.
     */
    private Set<TemplateProcessor> getTemplateProcessors() {
        final Set<TemplateProcessor> templateProcessors = Sets.newLinkedHashSet();

        templateProcessors.addAll(Providers.getCustomProviders(serviceLocator, TemplateProcessor.class));
        templateProcessors.addAll(Providers.getProviders(serviceLocator, TemplateProcessor.class));

        return templateProcessors;
    }

    /**
     * Get {@link ViewableContext viewable context}. User defined (custom) contexts have higher priority than the default ones
     * (i.e. {@link ResolvingViewableContext}).
     *
     * @return {@code non-null} viewable context.
     */
    private ViewableContext getViewableContext() {
        final Set<ViewableContext> customProviders = Providers.getCustomProviders(serviceLocator, ViewableContext.class);
        if (!customProviders.isEmpty()) {
            return customProviders.iterator().next();
        }
        return Providers.getProviders(serviceLocator, ViewableContext.class).iterator().next();
    }

    /**
     * Get resolving class for given viewable. Priority of obtaining is as follows:
     * <ul>
     *     <li>{@link org.glassfish.jersey.server.mvc.Viewable#getResolvingClass()}</li>
     *     <li>{@link org.glassfish.jersey.server.mvc.Template#resolvingClass()}</li>
     *     <li>{@link javax.ws.rs.container.ResourceInfo#getResourceClass()}</li>
     * </ul>
     *
     * @param viewable viewable.
     * @param template appropriate {@link Template} annotation.
     * @param resourceInfo resource info.
     * @return {@code non-null} resolving class.
     */
    private Class<?> getResolvingClass(final Viewable viewable, final Template template, final ResourceInfo resourceInfo) {
        Class<?> resolvingClass;

        if (viewable.getResolvingClass() != null) {
            resolvingClass = viewable.getResolvingClass();
        } else if (template != null && !Object.class.equals(template.resolvingClass())) {
            resolvingClass = template.resolvingClass();
        } else {
            resolvingClass = resourceInfo.getResourceClass();
        }

        return resolvingClass;
    }
}
