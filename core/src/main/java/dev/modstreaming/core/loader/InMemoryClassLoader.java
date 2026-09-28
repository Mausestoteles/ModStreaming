package dev.modstreaming.core.loader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defines classes and resources directly from memory. Nothing is ever written to disk.
 *
 * <p>The loader <em>owns</em> exactly the classes contained in the streamed jar: those are always
 * defined here (self-first), everything else is delegated to the parent (the public mod's class
 * loader), so streamed code can freely use the public jar, the API and Minecraft classes while
 * service interfaces keep their identity from the parent.</p>
 */
public final class InMemoryClassLoader extends ClassLoader {

    static {
        registerAsParallelCapable();
    }

    public static final String URL_PROTOCOL = "modstreaming";

    private final String artifactId;
    private final Map<String, byte[]> classes;
    private final Map<String, byte[]> resources;
    private final ProtectionDomain protectionDomain;
    private final URLStreamHandler handler = new MemoryStreamHandler();
    private final Set<String> definedPackages = ConcurrentHashMap.newKeySet();

    public InMemoryClassLoader(String artifactId, JarContents contents, ClassLoader parent) {
        super("modstreaming:" + artifactId, parent);
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        Map<String, byte[]> cls = new HashMap<>();
        Map<String, byte[]> res = new HashMap<>();
        for (Map.Entry<String, byte[]> e : contents.entries().entrySet()) {
            res.put(e.getKey(), e.getValue());
            String className = JarContents.classNameOf(e.getKey());
            if (className != null) {
                cls.put(className, e.getValue());
            }
        }
        this.classes = Collections.unmodifiableMap(cls);
        this.resources = Collections.unmodifiableMap(res);
        this.protectionDomain = new ProtectionDomain(new CodeSource(rootUrl(), (Certificate[]) null), null, this, null);
    }

    public String artifactId() {
        return artifactId;
    }

    /** Binary names of every class this loader owns. */
    public Set<String> classNames() {
        return classes.keySet();
    }

    public boolean owns(String className) {
        return classes.containsKey(className);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (classes.containsKey(name)) {
                    c = findClass(name);
                } else {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        definePackageIfNeeded(name);
        try {
            return defineClass(name, bytes, 0, bytes.length, protectionDomain);
        } catch (LinkageError e) {
            Class<?> already = findLoadedClass(name);
            if (already != null) {
                return already;
            }
            throw e;
        }
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] data = resources.get(name);
        if (data != null) {
            return new ByteArrayInputStream(data);
        }
        return super.getResourceAsStream(name);
    }

    @Override
    protected URL findResource(String name) {
        return resources.containsKey(name) ? urlFor(name) : null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        URL url = findResource(name);
        return url == null ? Collections.emptyEnumeration() : Collections.enumeration(Collections.singletonList(url));
    }

    private void definePackageIfNeeded(String className) {
        int idx = className.lastIndexOf('.');
        if (idx < 0) {
            return;
        }
        String pkg = className.substring(0, idx);
        if (definedPackages.add(pkg) && getDefinedPackage(pkg) == null) {
            try {
                definePackage(pkg, null, null, null, null, null, null, null);
            } catch (IllegalArgumentException ignored) {
                // defined concurrently
            }
        }
    }

    private URL rootUrl() {
        return urlFor("");
    }

    @SuppressWarnings("deprecation")
    private URL urlFor(String name) {
        try {
            return new URL(URL_PROTOCOL, artifactId, -1, "/" + name, handler);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private final class MemoryStreamHandler extends URLStreamHandler {
        @Override
        protected URLConnection openConnection(URL u) throws IOException {
            String path = u.getPath();
            String name = path.startsWith("/") ? path.substring(1) : path;
            byte[] data = resources.get(name);
            if (data == null) {
                throw new IOException("no such in-memory resource: " + name);
            }
            return new URLConnection(u) {
                @Override
                public void connect() {
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(data);
                }

                @Override
                public int getContentLength() {
                    return data.length;
                }
            };
        }
    }
}
