/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.ee.component;

import org.jboss.as.ee.naming.RootContextService;
import org.jboss.as.naming.ManagedReferenceFactory;
import org.jboss.as.naming.NamingStore;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.naming.deployment.JndiNamingDependencyProcessor;
import org.jboss.as.naming.service.BinderService;
import org.jboss.as.server.CurrentServiceContainer;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.logging.Logger;
import org.jboss.modules.Module;
import org.jboss.msc.service.DuplicateServiceException;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;

import java.util.List;
import java.util.Set;

import static org.jboss.as.ee.component.Attachments.EE_MODULE_CONFIGURATION;
import static org.jboss.as.server.deployment.Attachments.MODULE;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class ComponentInstallProcessor implements DeploymentUnitProcessor {


    private static final Logger logger = Logger.getLogger(ComponentInstallProcessor.class);

    public void deploy(final DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final Module module = deploymentUnit.getAttachment(MODULE);
        if (module == null) {
            // Nothing to do
            return;
        }
        final EEModuleConfiguration moduleDescription = deploymentUnit.getAttachment(EE_MODULE_CONFIGURATION);

        final Set<ServiceName> dependencies = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.JNDI_DEPENDENCIES);

        final ServiceName bindingDependencyService = JndiNamingDependencyProcessor.serviceName(deploymentUnit);

        // Iterate through each component, installing it into the container
        for (ComponentConfiguration configuration : moduleDescription.getComponentConfigurations()) {
            try {
                logger.tracef("Installing component %s", configuration.getComponentClass().getName());
                deployComponent(phaseContext, configuration, dependencies, bindingDependencyService);
            } catch (RuntimeException e) {
                throw new DeploymentUnitProcessingException("Failed to install component " + configuration, e);
            }
        }
    }

    protected void deployComponent(final DeploymentPhaseContext phaseContext, final ComponentConfiguration configuration, final Set<ServiceName> dependencies, final ServiceName bindingDependencyService) throws DeploymentUnitProcessingException {

        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final ServiceTarget serviceTarget = phaseContext.getServiceTarget();

        final String applicationName = configuration.getApplicationName();
        final String moduleName = configuration.getModuleName();
        final String componentName = configuration.getComponentName();
        final ServiceName baseName = configuration.getComponentDescription().getServiceName();
        final EEApplicationDescription applicationDescription = deploymentUnit.getAttachment(Attachments.EE_APPLICATION_DESCRIPTION);

        //create additional injectors

        final ServiceName createServiceName = configuration.getComponentDescription().getCreateServiceName();
        final ServiceName startServiceName = configuration.getComponentDescription().getStartServiceName();
        final BasicComponentCreateService createService = configuration.getComponentCreateServiceFactory().constructService(configuration);
        final ServiceBuilder<Component> createBuilder = serviceTarget.addService(createServiceName, createService);
        // inject the DU
        createBuilder.addDependency(deploymentUnit.getServiceName(), DeploymentUnit.class, createService.getDeploymentUnitInjector());

        final ComponentStartService startService = new ComponentStartService();
        final ServiceBuilder<Component> startBuilder = serviceTarget.addService(startServiceName, startService);
        final EEModuleConfiguration moduleConfiguration = deploymentUnit.getAttachment(Attachments.EE_MODULE_CONFIGURATION);

        if (moduleConfiguration == null) {
            return;
        }
        // Add all service dependencies
        for (DependencyConfigurator configurator : configuration.getCreateDependencies()) {
            configurator.configureDependency(createBuilder, createService);
        }
        for (DependencyConfigurator configurator : configuration.getStartDependencies()) {
            configurator.configureDependency(startBuilder, startService);
        }

        // START depends on CREATE
        startBuilder.addDependency(createServiceName, BasicComponent.class, startService.getComponentInjector());

        //don't start components until all bindings are up
        startBuilder.addDependency(bindingDependencyService);
        final ServiceName contextServiceName;
        //set up the naming context if nessesary
        if (configuration.getComponentDescription().getNamingMode() == ComponentNamingMode.CREATE) {
            final RootContextService contextService = new RootContextService();
            contextServiceName = ContextNames.contextServiceNameOfComponent(configuration.getApplicationName(), configuration.getModuleName(), configuration.getComponentName());
            serviceTarget.addService(contextServiceName, contextService).install();
        } else {
            contextServiceName = ContextNames.contextServiceNameOfModule(configuration.getApplicationName(), configuration.getModuleName());
        }

        final InjectionSource.ResolutionContext resolutionContext = new InjectionSource.ResolutionContext(
                configuration.getComponentDescription().getNamingMode() == ComponentNamingMode.USE_MODULE,
                configuration.getComponentName(),
                configuration.getModuleName(),
                configuration.getApplicationName()
        );

        // Iterate through each view, creating the services for each
        for (ViewConfiguration viewConfiguration : configuration.getViews()) {
            final ServiceName serviceName = viewConfiguration.getViewServiceName();
            final ViewService viewService = new ViewService(viewConfiguration);
            serviceTarget.addService(serviceName, viewService)
                    .addDependency(createServiceName, Component.class, viewService.getComponentInjector())
                    .install();

            // The bindings for the view
            for (BindingConfiguration bindingConfiguration : viewConfiguration.getBindingConfigurations()) {
                final String bindingName = bindingConfiguration.getName();
                final ServiceName binderServiceName = ContextNames.serviceNameOfContext(applicationName, moduleName, componentName, bindingName);
                final ServiceName namingStoreName = ContextNames.serviceNameOfNamingStore(applicationName, moduleName, componentName, bindingName);
                final BinderService service = new BinderService(bindingName, bindingConfiguration.getSource());

                //these bindings should never be merged, if a view binding is duplicated it is an error
                dependencies.add(binderServiceName);

                ServiceBuilder<ManagedReferenceFactory> serviceBuilder = serviceTarget.addService(binderServiceName, service);
                bindingConfiguration.getSource().getResourceValue(resolutionContext, serviceBuilder, phaseContext, service.getManagedObjectInjector());
                serviceBuilder.addDependency(namingStoreName, NamingStore.class, service.getNamingStoreInjector());
                serviceBuilder.install();
            }
        }

        if (configuration.getComponentDescription().getNamingMode() == ComponentNamingMode.CREATE) {
            // The bindings for the component
            processBindings(phaseContext, configuration, serviceTarget, contextServiceName, resolutionContext, configuration.getComponentDescription().getBindingConfigurations(), dependencies);


            // The bindings for the component class
            new ClassDescriptionTraversal(configuration.getModuleClassConfiguration(), applicationDescription) {
                @Override
                protected void handle(final EEModuleClassConfiguration classConfiguration, final EEModuleClassDescription classDescription) throws DeploymentUnitProcessingException {
                    processBindings(phaseContext, configuration, serviceTarget, contextServiceName, resolutionContext, classConfiguration.getBindingConfigurations(), dependencies);
                }
            }.run();


            for (InterceptorDescription interceptor : configuration.getComponentDescription().getAllInterceptors()) {
                final EEModuleClassConfiguration interceptorClass = applicationDescription.getClassConfiguration(interceptor.getInterceptorClassName());

                if (interceptorClass != null) {
                    new ClassDescriptionTraversal(interceptorClass, applicationDescription) {
                        @Override
                        protected void handle(final EEModuleClassConfiguration classConfiguration, final EEModuleClassDescription classDescription) throws DeploymentUnitProcessingException {
                            processBindings(phaseContext, configuration, serviceTarget, contextServiceName, resolutionContext, classConfiguration.getBindingConfigurations(), dependencies);
                        }
                    }.run();
                }
            }
        }

        createBuilder.install();
        startBuilder.install();
    }

    private void processBindings(DeploymentPhaseContext phaseContext, ComponentConfiguration configuration, ServiceTarget serviceTarget, ServiceName contextServiceName, InjectionSource.ResolutionContext resolutionContext, List<BindingConfiguration> bindings, final Set<ServiceName> dependencies) throws DeploymentUnitProcessingException {

        //we only handle java:comp bindings for components that have their own namespace here, the rest are processed by ModuleJndiBindingProcessor
        for (BindingConfiguration bindingConfiguration : bindings) {
            if (bindingConfiguration.getName().startsWith("java:comp") || !bindingConfiguration.getName().startsWith("java:")) {
                final String bindingName = bindingConfiguration.getName().startsWith("java:comp") ? bindingConfiguration.getName() : "java:comp/env/" + bindingConfiguration.getName();
                final ServiceName binderServiceName = ContextNames.serviceNameOfEnvEntry(configuration.getApplicationName(), configuration.getModuleName(), configuration.getComponentName(), configuration.getComponentDescription().getNamingMode() == ComponentNamingMode.CREATE, bindingName);

                try {
                    final BinderService service = new BinderService(bindingName, bindingConfiguration.getSource());
                    dependencies.add(binderServiceName);
                    ServiceBuilder<ManagedReferenceFactory> serviceBuilder = serviceTarget.addService(binderServiceName, service);
                    bindingConfiguration.getSource().getResourceValue(resolutionContext, serviceBuilder, phaseContext, service.getManagedObjectInjector());
                    serviceBuilder.addDependency(contextServiceName, NamingStore.class, service.getNamingStoreInjector());
                    serviceBuilder.install();
                } catch (DuplicateServiceException e) {
                    ServiceController<ManagedReferenceFactory> registered = (ServiceController<ManagedReferenceFactory>) CurrentServiceContainer.getServiceContainer().getService(binderServiceName);
                    if (registered == null)
                        throw e;

                    BinderService service = (BinderService) registered.getService();
                    if (!service.getSource().equals(bindingConfiguration.getSource()))
                        throw new IllegalArgumentException("Incompatible conflicting binding at " + bindingName + " source: " + bindingConfiguration.getSource());
                }
            }
        }
    }

    public void undeploy(final DeploymentUnit context) {
    }
}
