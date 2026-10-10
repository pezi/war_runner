package app.flutterdev.addressbook;

import java.io.File;
import java.util.HashMap;
import java.util.Set;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import com.vaadin.flow.di.DefaultInstantiator;
import com.vaadin.flow.di.Instantiator;
import com.vaadin.flow.di.Lookup;
import com.vaadin.flow.di.LookupInitializer;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.router.InternalServerError;
import com.vaadin.flow.router.RouteNotFoundError;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.startup.ErrorNavigationTargetInitializer;
import com.vaadin.flow.server.startup.RouteRegistryInitializer;
import com.vaadin.flow.server.startup.VaadinAppShellInitializer;
import com.vaadin.flow.server.startup.VaadinInitializerException;

/**
 * Explicit bootstrap replaces classpath/annotation scanning on Android's DEX runtime.
 * The repository is created once per web app and handed to the view by a custom instantiator,
 * which is what Spring would do through constructor injection.
 */
public final class AddressBookServlet extends VaadinServlet {
    /** WAR Runner's storage contract: the web app's persistent data directory. */
    static final String DATA_DIRECTORY = "warrunner.dataDirectory";

    private PersonRepository repository;

    /**
     * The host publishes the directory as a {@link File} attribute, a context init parameter and
     * a system property. Returns {@code null} on a container without the contract.
     */
    static File dataDirectory(ServletContext context) {
        Object attribute = context.getAttribute(DATA_DIRECTORY);
        if (attribute instanceof File file) {
            return file;
        }
        String path = context.getInitParameter(DATA_DIRECTORY);
        if (path == null) {
            path = System.getProperty(DATA_DIRECTORY);
        }
        return path == null ? null : new File(path);
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        VaadinServletContext context = new VaadinServletContext(config.getServletContext());
        if (context.getAttribute(Lookup.class) == null) {
            new LookupInitializer().initialize(context, new HashMap<>(),
                    lookup -> context.setAttribute(Lookup.class, lookup));
            try {
                new RouteRegistryInitializer().initialize(Set.of(AddressBookView.class), context);
            } catch (VaadinInitializerException failure) {
                throw new ServletException("Cannot register the address book route", failure);
            }
            new ErrorNavigationTargetInitializer().initialize(
                    Set.of(RouteNotFoundError.class, InternalServerError.class), context);
            VaadinAppShellInitializer.init(Set.of(AppShell.class), context);
        }
        repository = new PersonRepository(dataDirectory(config.getServletContext()));
        System.out.println("Address book database " + repository.location() + " ready with "
                + repository.count() + " contacts");
        super.init(config);
    }

    @Override
    public void destroy() {
        super.destroy();
        if (repository != null) {
            repository.close();
        }
    }

    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration configuration)
            throws ServiceException {
        VaadinServletService service = new VaadinServletService(this, configuration) {
            @Override
            protected java.util.concurrent.Executor createDefaultExecutor() {
                // Android has no virtual threads. The process owns this pool.
                return java.util.concurrent.ForkJoinPool.commonPool();
            }

            @Override
            protected Instantiator createInstantiator() {
                return new DefaultInstantiator(this) {
                    @Override
                    public <T> T getOrCreate(Class<T> type) {
                        if (type == AddressBookView.class) {
                            return type.cast(new AddressBookView(repository));
                        }
                        return super.getOrCreate(type);
                    }
                };
            }
        };
        service.init();
        return service;
    }
}
