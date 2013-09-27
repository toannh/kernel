/*
 * Copyright (C) 2013 eXo Platform SAS.
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
package org.exoplatform.container;

import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.commons.utils.SecurityHelper;
import org.exoplatform.container.management.ManageableComponentAdapterFactoryMT;
import org.exoplatform.container.spi.ComponentAdapter;
import org.exoplatform.container.spi.ComponentAdapterFactory;
import org.exoplatform.container.spi.ContainerException;
import org.exoplatform.container.util.ContainerUtil;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.picocontainer.Startable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Qualifier;

/**
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class ConcurrentContainerMT extends ConcurrentContainer
{

   /**
    * The serial version UID
    */
   private static final long serialVersionUID = -1059330085804288350L;

   private static final Log LOG = ExoLogger.getLogger("exo.kernel.container.mt.ConcurrentContainerMT");

   private static volatile transient ExecutorService EXECUTOR;

   private final transient ThreadLocal<ComponentTaskContext> currentCtx = new ThreadLocal<ComponentTaskContext>();

   /**
    * The name of the system parameter to indicate the total amount of threads to use for the kernel
    */
   public static final String THREAD_POOL_SIZE_PARAM_NAME = "org.exoplatform.container.mt.tps";

   /**
    * Used to detect all the dependencies not properly defined
    */
   protected final transient ThreadLocal<Deque<DependencyStack>> dependencyStacks = Mode
      .hasMode(Mode.AUTO_SOLVE_DEP_ISSUES) ? new ThreadLocal<Deque<DependencyStack>>() : null;

   private static ExecutorService getExecutor()
   {
      if (EXECUTOR == null)
      {
         synchronized (ConcurrentContainerMT.class)
         {
            if (EXECUTOR == null)
            {
               String sValue = PropertyManager.getProperty(THREAD_POOL_SIZE_PARAM_NAME);
               int threadPoolSize;
               if (sValue != null)
               {
                  LOG.debug("A value for the thread pool size has been found, it has been set to '" + sValue + "'");
                  threadPoolSize = Integer.parseInt(sValue);
               }
               else
               {
                  threadPoolSize = Runtime.getRuntime().availableProcessors();
               }
               LOG.debug("The size of the thread pool used by the kernel has been set to " + threadPoolSize);
               EXECUTOR = Executors.newFixedThreadPool(threadPoolSize, new KernelThreadFactory());
            }
         }
      }
      return EXECUTOR;
   }

   /**
    * Creates a new container with the default {@link ComponentAdapterFactory} and a parent container.
    */
   public ConcurrentContainerMT()
   {
   }

   /**
    * Creates a new container with the default {@link ComponentAdapterFactory} and a parent container.
    *
    * @param holder                  the holder of the container
    * @param parent                  the parent container (used for component dependency lookups).
    */
   public ConcurrentContainerMT(ExoContainer holder, ExoContainer parent)
   {
      setParent(parent);
      setHolder(holder);
   }

   @Override
   protected ComponentAdapterFactory getDefaultComponentAdapterFactory()
   {
      return new ManageableComponentAdapterFactoryMT(holder, this);
   }

   public <T> T getComponentInstanceOfType(Class<T> componentType)
   {
      Deque<DependencyStack> stacks = dependencyStacks != null ? dependencyStacks.get() : null;
      DependencyStack stack = null;
      T instance;
      try
      {
         if (stacks != null)
         {
            stack = stacks.getLast();
            stack.add(new DependencyByType(componentType));
         }
         instance = super.getComponentInstanceOfType(componentType);
      }
      finally
      {
         if (stack != null && !stack.isEmpty())
         {
            stack.removeLast();
         }
      }
      return instance;
   }

   /**
    * @see org.exoplatform.container.ConcurrentContainer#getComponentInstance(java.lang.Object, java.lang.Class)
    */
   @Override
   public <T> T getComponentInstance(Object componentKey, Class<T> bindType) throws ContainerException
   {
      Deque<DependencyStack> stacks = dependencyStacks != null ? dependencyStacks.get() : null;
      DependencyStack stack = null;
      T instance;
      try
      {
         if (stacks != null)
         {
            stack = stacks.getLast();
            if (componentKey instanceof String)
            {
               stack.add(new DependencyByName((String)componentKey, bindType));
            }
            else if (componentKey instanceof Class<?>)
            {
               Class<?> type = (Class<?>)componentKey;
               if (type.isAnnotation())
               {
                  stack.add(new DependencyByQualifier(type, bindType));
               }
               else
               {
                  stack.add(new DependencyByType(type));
               }
            }
            else
            {
               stack = null;
            }
         }
         instance = super.getComponentInstance(componentKey, bindType);
      }
      finally
      {
         if (stack != null && !stack.isEmpty())
         {
            stack.removeLast();
         }
      }
      return instance;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   protected <T> T getComponentInstanceFromContext(ComponentAdapter<T> componentAdapter, Class<T> bindType)
   {
      ComponentTaskContext ctx = currentCtx.get();
      if (ctx != null)
      {
         T result = ctx.getComponentInstanceFromContext(componentAdapter.getComponentKey(), bindType);
         if (result != null)
         {
            // Don't keep in cache a component that has not been created yet
            getCache().disable();
            return result;
         }
      }
      return null;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public <T> CreationalContextComponentAdapter<T> addComponentToCtx(Object key)
   {
      ComponentTaskContext ctx = currentCtx.get();
      return ctx.addComponentToContext(key, new CreationalContextComponentAdapter<T>());
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public void removeComponentFromCtx(Object key)
   {
      ComponentTaskContext ctx = currentCtx.get();
      ctx.removeComponentFromContext(key);
   }

   /**
    * Start the components of this Container and all its logical child containers.
    * Any component implementing the lifecycle interface {@link org.picocontainer.Startable} will be started.
    */
   public void start()
   {
      // First, create and initialize the components
      getComponentInstancesOfType(Startable.class);
      List<ComponentAdapter<Startable>> adapters = getComponentAdaptersOfType(Startable.class);
      final Map<ComponentAdapter<Startable>, Object> alreadyStarted =
         new ConcurrentHashMap<ComponentAdapter<Startable>, Object>();
      final AtomicReference<Exception> error = new AtomicReference<Exception>();
      start(adapters, alreadyStarted, error);
      if (error.get() != null)
      {
         throw new RuntimeException("Could not start the container", error.get());
      }
   }

   /**
    * Starts all the provided adapters
    */
   protected void start(Collection<ComponentAdapter<Startable>> adapters,
      final Map<ComponentAdapter<Startable>, Object> alreadyStarted, final AtomicReference<Exception> error)
   {
      if (adapters == null || adapters.isEmpty())
         return;
      boolean enableMultiThreading = Mode.hasMode(Mode.MULTI_THREADED) && adapters.size() > 1;
      List<Future<?>> submittedTasks = null;
      for (final ComponentAdapter<Startable> adapter : adapters)
      {
         if (error.get() != null)
            break;
         if (alreadyStarted.containsKey(adapter))
         {
            // The component has already been started
            continue;
         }
         if (enableMultiThreading)
         {
            final ExoContainer container = ExoContainerContext.getCurrentContainerIfPresent();
            final ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Callable<Object> task = new Callable<Object>()
            {
               public Object call() throws Exception
               {
                  try
                  {
                     return SecurityHelper.doPrivilegedExceptionAction(new PrivilegedExceptionAction<Void>()
                     {
                        public Void run() throws Exception
                        {
                           if (error.get() != null)
                           {
                              return null;
                           }
                           else if (alreadyStarted.containsKey(adapter))
                           {
                              // The component has already been started
                              return null;
                           }
                           if (adapter instanceof ComponentAdapterDependenciesAware)
                           {
                              ComponentAdapterDependenciesAware<Startable> cada = (ComponentAdapterDependenciesAware<Startable>)adapter;
                              start(getStartableDependencies(cada.getCreateDependencies()), alreadyStarted, error);
                           }
                           synchronized (adapter)
                           {
                              if (alreadyStarted.containsKey(adapter))
                              {
                                 // The component has already been started
                                 return null;
                              }
                              ExoContainer oldContainer = ExoContainerContext.getCurrentContainerIfPresent();
                              ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
                              try
                              {
                                 ExoContainerContext.setCurrentContainer(container);
                                 Thread.currentThread().setContextClassLoader(cl);
                                 Startable startable = adapter.getComponentInstance();
                                 startable.start();
                              }
                              catch (Exception e)
                              {
                                 error.compareAndSet(null, e);
                              }
                              finally
                              {
                                 Thread.currentThread().setContextClassLoader(oldCl);
                                 ExoContainerContext.setCurrentContainer(oldContainer);
                              }
                              return null;
                           }
                        }
                     });
                  }
                  catch (PrivilegedActionException pae)
                  {
                     Throwable cause = pae.getCause();
                     if (cause instanceof Exception)
                     {
                        throw (Exception)cause;
                     }
                     throw new Exception(cause);
                  }
               }
            };
            if (submittedTasks == null)
            {
               submittedTasks = new ArrayList<Future<?>>();
            }
            submittedTasks.add(getExecutor().submit(task));
         }
         else
         {
            if (adapter instanceof ComponentAdapterDependenciesAware)
            {
               ComponentAdapterDependenciesAware<Startable> cada = (ComponentAdapterDependenciesAware<Startable>)adapter;
               start(getStartableDependencies(cada.getCreateDependencies()), alreadyStarted, error);
            }
            synchronized (adapter)
            {
               if (alreadyStarted.containsKey(adapter))
               {
                  // The component has already been started
                  continue;
               }
               try
               {
                  Startable startable = adapter.getComponentInstance();
                  startable.start();
               }
               catch (Exception e)
               {
                  error.compareAndSet(null, e);
               }
            }
         }
      }
      if (submittedTasks != null)
      {
         for (int i = 0, length = submittedTasks.size(); i < length; i++)
         {
            Future<?> task = submittedTasks.get(i);
            try
            {
               task.get();
            }
            catch (ExecutionException e)
            {
               Throwable cause = e.getCause();
               if (cause instanceof RuntimeException)
               {
                  throw (RuntimeException)cause;
               }
               throw new RuntimeException(cause);
            }
            catch (InterruptedException e)
            {
               Thread.currentThread().interrupt();
            }
         }
      }
   }

   @SuppressWarnings("unchecked")
   private Collection<ComponentAdapter<Startable>> getStartableDependencies(Collection<Dependency> dependencies)
   {
      if (dependencies == null || dependencies.isEmpty())
         return null;
      Collection<ComponentAdapter<Startable>> result = new ArrayList<ComponentAdapter<Startable>>();
      for (Dependency dep : dependencies)
      {
         if (dep.isLazy())
            continue;
         ComponentAdapter<?> adapter = dep.getAdapter(holder);
         boolean isLocal = componentAdapters.contains(adapter);
         if (!isLocal)
         {
            // To prevent infinite loop we assume that component adapters of
            // parent container are already started so we skip them
            continue;
         }
         else if (!Startable.class.isAssignableFrom(adapter.getComponentImplementation()))
         {
            // The dependency is not startable
            continue;
         }
         result.add((ComponentAdapter<Startable>)adapter);
      }
      return result;
   }

   @SuppressWarnings("unchecked")
   public <T> Constructor<T> getConstructor(Class<T> clazz, List<Dependency> dependencies) throws Exception
   {
      Constructor<?>[] constructors = new Constructor<?>[0];
      try
      {
         constructors = ContainerUtil.getSortedConstructors(clazz);
      }
      catch (NoClassDefFoundError err)
      {
         throw new Exception("Cannot resolve constructor for class " + clazz.getName(), err);
      }
      Class<?> unknownParameter = null;
      for (int k = 0; k < constructors.length; k++)
      {
         Constructor<?> constructor = constructors[k];
         Class<?>[] parameters = constructor.getParameterTypes();
         Object[] args = new Object[parameters.length];
         boolean constructorWithInject = constructors.length == 1 && constructor.isAnnotationPresent(Inject.class);
         boolean satisfied = true;
         String logMessagePrefix = null;
         Type[] genericTypes = null;
         Annotation[][] parameterAnnotations = null;
         if (constructorWithInject)
         {
            genericTypes = constructor.getGenericParameterTypes();
            parameterAnnotations = constructor.getParameterAnnotations();
         }
         if (LOG.isDebugEnabled() && constructorWithInject)
         {
            logMessagePrefix = "Could not call the constructor of the class " + clazz.getName();
         }
         for (int i = 0; i < args.length; i++)
         {
            if (!parameters[i].equals(InitParams.class))
            {
               if (constructorWithInject)
               {
                  Object result =
                     resolveType(parameters[i], genericTypes[i], parameterAnnotations[i], logMessagePrefix,
                        dependencies);
                  if (!(result instanceof Integer))
                  {
                     args[i] = result;
                  }
               }
               else
               {
                  final Class<?> componentType = parameters[i];
                  args[i] = holder.getComponentAdapterOfType(componentType);
                  dependencies.add(new DependencyByType(componentType));
               }
               if (args[i] == null)
               {
                  satisfied = false;
                  unknownParameter = parameters[i];
                  dependencies.clear();
                  break;
               }
            }
         }
         if (satisfied)
         {
            if ((!Modifier.isPublic(constructor.getModifiers()) || !Modifier.isPublic(constructor.getDeclaringClass()
               .getModifiers())) && !constructor.isAccessible())
               constructor.setAccessible(true);
            return (Constructor<T>)constructor;
         }
      }
      throw new Exception("Cannot find a satisfying constructor for " + clazz + " with parameter " + unknownParameter);
   }

   /**
    * Initializes the instance by injecting objects into fields and the methods with the
    * annotation {@link Inject}
    * @return <code>true</code> if at least Inject annotation has been found, <code>false</code> otherwise
    */
   public <T> boolean initializeComponent(Class<T> targetClass, List<Dependency> dependencies,
      List<ComponentTask<Void>> componentInitTasks, DependencyStackListener caller)
   {
      LinkedList<Class<?>> hierarchy = new LinkedList<Class<?>>();
      Class<?> clazz = targetClass;
      do
      {
         hierarchy.addFirst(clazz);
      }
      while (!(clazz = clazz.getSuperclass()).equals(Object.class));
      // Fields and methods in superclasses are injected before those in subclasses. 
      Map<String, Method> methodAlreadyRegistered = new HashMap<String, Method>();
      Map<Class<?>, Collection<Method>> methodsPerClass = new HashMap<Class<?>, Collection<Method>>();
      for (Class<?> c : hierarchy)
      {
         addMethods(c, methodAlreadyRegistered, methodsPerClass);
      }
      boolean isInjectPresent = !methodAlreadyRegistered.isEmpty();
      for (Class<?> c : hierarchy)
      {
         if (initializeFields(targetClass, c, dependencies, componentInitTasks, caller))
         {
            isInjectPresent = true;
         }
         initializeMethods(targetClass, methodsPerClass.get(c), dependencies, componentInitTasks, caller);
      }
      return isInjectPresent;
   }

   /**
    * Initializes the instance by calling all the methods with the
    * annotation {@link Inject}
    */
   private <T> void initializeMethods(final Class<T> targetClass, Collection<Method> methods,
      List<Dependency> dependencies, List<ComponentTask<Void>> componentInitTasks, DependencyStackListener caller)
   {
      if (methods == null)
      {
         return;
      }
      main : for (final Method m : methods)
      {
         if (m.isAnnotationPresent(Inject.class))
         {
            if (Modifier.isAbstract(m.getModifiers()))
            {
               LOG.warn("Could not call the method " + m.getName() + " of the class " + targetClass.getName()
                  + ": The method cannot be abstract");
               continue;
            }
            else if (Modifier.isStatic(m.getModifiers()))
            {
               LOG.warn("Could not call the method " + m.getName() + " of the class " + targetClass.getName()
                  + ": The method cannot be static");
               continue;
            }
            // The method is annotated with Inject and is not abstract and has not been called yet
            Class<?>[] paramTypes = m.getParameterTypes();
            final Object[] params = new Object[paramTypes.length];
            Type[] genericTypes = m.getGenericParameterTypes();
            Annotation[][] parameterAnnotations = m.getParameterAnnotations();
            String logMessagePrefix = null;
            if (LOG.isDebugEnabled())
            {
               logMessagePrefix = "Could not call the method " + m.getName() + " of the class " + targetClass.getName();
            }
            for (int j = 0, l = paramTypes.length; j < l; j++)
            {
               Object result =
                  resolveType(paramTypes[j], genericTypes[j], parameterAnnotations[j], logMessagePrefix, dependencies);
               if (result instanceof Integer)
               {
                  int r = (Integer)result;
                  if (r == 1 || r == 2)
                  {
                     continue main;
                  }
                  params[j] = null;
                  continue;
               }
               else
               {
                  params[j] = dependencies.get(dependencies.size() - 1);
               }
            }
            try
            {
               if ((!Modifier.isPublic(m.getModifiers()) || !Modifier.isPublic(m.getDeclaringClass().getModifiers()))
                  && !m.isAccessible())
                  m.setAccessible(true);
               componentInitTasks.add(new ComponentTask<Void>("Call the method " + m.getName() + " of the class "
                  + targetClass.getName(), this, caller, ComponentTaskType.INIT)
               {
                  public Void execute(CreationalContextComponentAdapter<?> cCtx) throws Exception
                  {
                     try
                     {
                        loadArguments(params);
                        m.invoke(cCtx.get(), params);
                     }
                     catch (Exception e)
                     {
                        throw new RuntimeException("Could not call the method " + m.getName() + " of the class "
                           + targetClass.getName() + ": " + e.getMessage(), e);
                     }
                     return null;
                  }
               });
            }
            catch (Exception e)
            {
               throw new RuntimeException("Could not call the method " + m.getName() + " of the class "
                  + targetClass.getName() + ": " + e.getMessage(), e);
            }
         }
      }
   }

   /**
    * Initializes the fields of the instance by injecting objects into fields with the
    * annotation {@link Inject} for a given class
    */
   private <T> boolean initializeFields(final Class<T> targetClass, Class<?> clazz, List<Dependency> dependencies,
      List<ComponentTask<Void>> componentInitTasks, DependencyStackListener caller)
   {
      boolean isInjectPresent = false;
      Field[] fields = clazz.getDeclaredFields();
      for (int i = 0, length = fields.length; i < length; i++)
      {
         final Field f = fields[i];
         if (f.isAnnotationPresent(Inject.class))
         {
            isInjectPresent = true;
            if (Modifier.isFinal(f.getModifiers()))
            {
               LOG.warn("Could not set a value to the field " + f.getName() + " of the class " + targetClass.getName()
                  + ": The field cannot be final");
               continue;
            }
            else if (Modifier.isStatic(f.getModifiers()))
            {
               LOG.warn("Could not set a value to the field " + f.getName() + " of the class " + targetClass.getName()
                  + ": The field cannot be static");
               continue;
            }
            // The field is annotated with Inject and is not final and/or static
            try
            {
               if ((!Modifier.isPublic(f.getModifiers()) || !Modifier.isPublic(f.getDeclaringClass().getModifiers()))
                  && !f.isAccessible())
                  f.setAccessible(true);
               String logMessagePrefix = null;
               if (LOG.isDebugEnabled())
               {
                  logMessagePrefix =
                     "Could not set a value to the field " + f.getName() + " of the class " + targetClass.getName();
               }
               Object result =
                  resolveType(f.getType(), f.getGenericType(), f.getAnnotations(), logMessagePrefix, dependencies);
               if (result instanceof Integer)
               {
                  continue;
               }
               final Dependency dependency = dependencies.get(dependencies.size() - 1);
               componentInitTasks.add(new ComponentTask<Void>("Set a value to the field " + f.getName()
                  + " of the class " + targetClass.getName(), this, caller, ComponentTaskType.INIT)
               {
                  public Void execute(CreationalContextComponentAdapter<?> cCtx) throws Exception
                  {
                     try
                     {
                        f.set(cCtx.get(), dependency.load(holder));
                     }
                     catch (Exception e)
                     {
                        throw new RuntimeException("Could not set a value to the field " + f.getName()
                           + " of the class " + targetClass.getName() + ": " + e.getMessage(), e);
                     }
                     return null;
                  }
               });
            }
            catch (Exception e)
            {
               throw new RuntimeException("Could not set a value to the field " + f.getName() + " of the class "
                  + targetClass.getName() + ": " + e.getMessage(), e);
            }
         }
      }
      return isInjectPresent;
   }

   /**
    * Resolves the given type and generic type
    */
   private Object resolveType(final Class<?> type, Type genericType, Annotation[] annotations, String logMessagePrefix,
      List<Dependency> dependencies)
   {
      if (type.isPrimitive())
      {
         if (LOG.isDebugEnabled())
         {
            LOG.debug(logMessagePrefix + ": Primitive types are not supported");
         }
         return 1;
      }
      Named named = null;
      Class<?> qualifier = null;
      for (int i = 0, length = annotations.length; i < length; i++)
      {
         Annotation a = annotations[i];
         if (a instanceof Named)
         {
            named = (Named)a;
            break;
         }
         else if (a.annotationType().isAnnotationPresent(Qualifier.class))
         {
            qualifier = a.annotationType();
            break;
         }
      }
      if (type.isInterface() && type.equals(Provider.class))
      {
         if (!(genericType instanceof ParameterizedType))
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug(logMessagePrefix + ": The generic type is not of type ParameterizedType");
            }
            return 2;
         }
         ParameterizedType aType = (ParameterizedType)genericType;
         Type[] typeVars = aType.getActualTypeArguments();
         Class<?> expectedType = (Class<?>)typeVars[0];
         final ComponentAdapter<?> adapter;
         final Object key;
         if (named != null)
         {
            adapter = holder.getComponentAdapter(key = named.value(), expectedType);
         }
         else if (qualifier != null)
         {
            adapter = holder.getComponentAdapter(key = qualifier, expectedType);
         }
         else
         {
            key = expectedType;
            adapter = holder.getComponentAdapterOfType(expectedType);
         }

         if (adapter == null)
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug(logMessagePrefix + ": We have no value to set so we skip it");
            }
            return 3;
         }
         final Provider<Object> result = new Provider<Object>()
         {
            public Object get()
            {
               return adapter.getComponentInstance();
            }
         };
         dependencies.add(new DependencyByProvider(key, expectedType, result));
         return result;
      }
      else
      {
         if (named != null)
         {
            final String name = named.value();
            dependencies.add(new DependencyByName(name, type));
            return holder.getComponentAdapter(name, type);
         }
         else if (qualifier != null)
         {
            dependencies.add(new DependencyByQualifier(qualifier, type));
            return holder.getComponentAdapter(qualifier, type);
         }
         else
         {
            dependencies.add(new DependencyByType(type));
            return holder.getComponentAdapterOfType(type);
         }
      }
   }

   public <T> T createComponent(Class<T> clazz) throws Exception
   {
      return createComponent(clazz, null);
   }

   public <T> T createComponent(Class<T> clazz, InitParams params) throws Exception
   {
      List<Dependency> dependencies = new ArrayList<Dependency>();
      Constructor<T> constructor = getConstructor(clazz, dependencies);
      final Object[] args = getArguments(constructor, params, dependencies);
      loadArguments(args);
      return constructor.getDeclaringClass().cast(constructor.newInstance(args));
   }

   public <T> ComponentTask<T> createComponentTask(final Constructor<T> constructor, InitParams params,
      List<Dependency> dependencies, DependencyStackListener caller) throws Exception
   {
      final Object[] args = getArguments(constructor, params, dependencies);
      return new ComponentTask<T>(this, caller, ComponentTaskType.CREATE)
      {
         public T execute(CreationalContextComponentAdapter<?> cCtx) throws Exception
         {
            loadArguments(args);
            return constructor.getDeclaringClass().cast(constructor.newInstance(args));
         }
      };
   }

   public void loadArguments(Object[] args)
   {
      try
      {
         for (int i = 0, length = args.length; i < length; i++)
         {
            if (args[i] instanceof Dependency)
            {
               args[i] = ((Dependency)args[i]).load(holder);
            }
         }
      }
      catch (Exception e)
      {
         throw new RuntimeException("Could not load the arguments", e);
      }
   }

   public void loadDependencies(Object originalComponentKey, final ComponentTaskContext ctx,
      Collection<Dependency> dependencies, final ComponentTaskType type) throws Exception
   {
      if (dependencies.isEmpty())
         return;
      List<Future<?>> submittedTasks = null;
      boolean enableMultiThreading = Mode.hasMode(Mode.MULTI_THREADED) && dependencies.size() > 1;
      for (final Dependency dependency : dependencies)
      {
         if (dependency.getKey().equals(originalComponentKey) || dependency.isLazy())
         {
            // Prevent infinite loop
            continue;
         }
         if (enableMultiThreading)
         {
            final ExoContainer container = ExoContainerContext.getCurrentContainerIfPresent();
            final ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Callable<Object> task = new Callable<Object>()
            {
               public Object call() throws Exception
               {
                  try
                  {
                     return SecurityHelper.doPrivilegedExceptionAction(new PrivilegedExceptionAction<Object>()
                     {
                        public Object run() throws Exception
                        {
                           ExoContainer oldContainer = ExoContainerContext.getCurrentContainerIfPresent();
                           ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
                           ComponentTaskContext previousCtx = currentCtx.get();
                           try
                           {
                              ExoContainerContext.setCurrentContainer(container);
                              Thread.currentThread().setContextClassLoader(cl);
                              currentCtx.set(ctx.addToContext(dependency.getKey(), type));
                              return dependency.load(holder);
                           }
                           finally
                           {
                              Thread.currentThread().setContextClassLoader(oldCl);
                              ExoContainerContext.setCurrentContainer(oldContainer);
                              currentCtx.set(previousCtx);
                           }
                        }
                     });
                  }
                  catch (PrivilegedActionException pae)
                  {
                     Throwable cause = pae.getCause();
                     if (cause instanceof Exception)
                     {
                        throw (Exception)cause;
                     }
                     throw new Exception(cause);
                  }
               }
            };
            if (submittedTasks == null)
            {
               submittedTasks = new ArrayList<Future<?>>();
            }
            submittedTasks.add(getExecutor().submit(task));
         }
         else
         {
            ComponentTaskContext previousCtx = currentCtx.get();
            try
            {
               currentCtx.set(ctx.addToContext(dependency.getKey(), type));
               dependency.load(holder);
            }
            finally
            {
               currentCtx.set(previousCtx);
            }
         }
      }
      if (submittedTasks != null)
      {
         for (int i = 0, length = submittedTasks.size(); i < length; i++)
         {
            Future<?> task = submittedTasks.get(i);
            try
            {
               task.get();
            }
            catch (ExecutionException e)
            {
               Throwable cause = e.getCause();
               if (cause instanceof Exception)
               {
                  throw (Exception)cause;
               }
               throw new Exception(cause);
            }
         }
      }
   }

   /**
    * Gives the current context
    */
   public ComponentTaskContext getComponentTaskContext()
   {
      return currentCtx.get();
   }

   /**
    * Set the current context
    */
   public void setComponentTaskContext(ComponentTaskContext ctx)
   {
      currentCtx.set(ctx);
   }

   protected <T> T execute(ComponentTask<T> task, CreationalContextComponentAdapter<?> cCtx) throws Exception
   {
      Deque<DependencyStack> stacks = null;
      try
      {
         if (dependencyStacks != null)
         {
            stacks = dependencyStacks.get();
            if (stacks == null)
            {
               stacks = new LinkedList<DependencyStack>();
               dependencyStacks.set(stacks);
            }
            DependencyStack stack = new DependencyStack(task);
            stacks.add(stack);
         }
         return task.execute(cCtx);
      }
      catch (InvocationTargetException e)
      {
         if (e.getCause() instanceof Exception)
         {
            throw (Exception)e.getCause();
         }
         throw e;
      }
      finally
      {
         if (dependencyStacks != null)
         {
            stacks.removeLast();
            if (stacks.isEmpty())
            {
               dependencyStacks.set(null);
            }
         }
      }
   }

   public <T> Object[] getArguments(Constructor<T> constructor, InitParams params, List<Dependency> dependencies)
   {
      Class<?>[] parameters = constructor.getParameterTypes();
      Object[] args = new Object[parameters.length];
      if (args.length == 0)
         return args;
      Iterator<Dependency> tasks = dependencies.iterator();
      for (int i = 0; i < parameters.length; i++)
      {
         final Class<?> parameter = parameters[i];
         if (parameter.equals(InitParams.class))
         {
            args[i] = params;
            continue;
         }
         args[i] = tasks.next();
      }
      return args;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public String getId()
   {
      return "ConcurrentContainer";
   }

   private static class KernelThreadFactory implements ThreadFactory
   {
      final ThreadGroup group;

      final AtomicInteger threadNumber = new AtomicInteger(1);

      final String namePrefix;

      KernelThreadFactory()
      {
         SecurityManager s = System.getSecurityManager();
         group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
         namePrefix = "kernel-thread-";
      }

      /**
       * {@inheritDoc}
       */
      public Thread newThread(Runnable r)
      {
         Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
         if (t.isDaemon())
            t.setDaemon(false);
         if (t.getPriority() != Thread.NORM_PRIORITY)
            t.setPriority(Thread.NORM_PRIORITY);
         return t;
      }
   }
}
