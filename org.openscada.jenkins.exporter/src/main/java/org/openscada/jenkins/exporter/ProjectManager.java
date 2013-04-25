/*
 * This file is part of the openSCADA project
 * 
 * Copyright (C) 2013 Jens Reimann (ctron@dentrassi.de)
 *
 * openSCADA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License version 3
 * only, as published by the Free Software Foundation.
 *
 * openSCADA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License version 3 for more details
 * (a copy is included in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU Lesser General Public License
 * version 3 along with openSCADA. If not, see
 * <http://opensource.org/licenses/lgpl-3.0.html> for a copy of the LGPLv3 License.
 */

package org.openscada.jenkins.exporter;

import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Run;

import java.util.HashMap;
import java.util.Map;

import jenkins.model.Jenkins;

import org.openscada.core.ConnectionInformation;
import org.openscada.core.Variant;
import org.openscada.da.server.common.DataItemCommand;
import org.openscada.da.server.common.DataItemCommand.Listener;
import org.openscada.da.server.common.exporter.ObjectExporter;
import org.openscada.da.server.common.item.factory.DefaultChainItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProjectManager
{

    private static final Logger logger = LoggerFactory.getLogger ( ProjectManager.class );

    private final Map<String, ProjectStateExporter> states = new HashMap<String, ProjectStateExporter> ();

    private final Exporter exporter;

    private int portNumber;

    private HiveImpl hive;

    private org.openscada.da.server.ngp.Exporter ngpExporter;

    private static class ProjectStateExporter
    {
        private final ProjectState state;

        private DefaultChainItemFactory itemFactory;

        private ObjectExporter stateExporter;

        private final String id;

        public ProjectStateExporter ( final String id )
        {
            this.id = id;
            this.state = new ProjectState ();
        }

        public void register ( final HiveImpl hive )
        {
            logger.info ( "Register {} to hive: {}", this.id, hive );

            this.itemFactory = new DefaultChainItemFactory ( hive, hive.getRootFolder (), this.id, this.id );
            this.stateExporter = new ObjectExporter ( this.itemFactory );
            this.stateExporter.attachTarget ( this.state );

            final AbstractProject<?, ?> job = Jenkins.getInstance ().getItemByFullName ( this.id, AbstractProject.class );
            if ( job != null )
            {
                final DataItemCommand startCommand = this.itemFactory.createCommand ( "start", null );
                startCommand.addListener ( new Listener () {

                    @Override
                    public void command ( final Variant value ) throws Exception
                    {
                        job.scheduleBuild ( new Cause.RemoteCause ( "unknown", "startCommand" ) );
                    }
                } );
            }

        }

        public void unregister ()
        {
            this.stateExporter.detachTarget ();
            this.itemFactory.dispose ();
        }

        public ProjectState getState ()
        {
            return this.state;
        }
    }

    public ProjectManager ( final Exporter exporter )
    {
        this.exporter = exporter;
    }

    public synchronized ProjectState addProject ( final String id, final Run<?, ?> run )
    {
        logger.info ( "Adding project: {}", id );

        ProjectStateExporter project = this.states.get ( id );
        if ( project != null )
        {
            return project.getState ();
        }

        project = new ProjectStateExporter ( id );

        new ProjectStateUpdater ( project.getState () ).update ( run );

        this.states.put ( id, project );

        if ( this.hive != null )
        {
            project.register ( this.hive );
        }

        return project.getState ();
    }

    public synchronized ProjectState getState ( final String id )
    {
        final ProjectStateExporter state = this.states.get ( id );
        if ( state == null )
        {
            return null;
        }
        return state.getState ();
    }

    public void setPort ( final int portNumber )
    {
        logger.debug ( "Setting port: {}", portNumber );

        try
        {
            destroyServer ();
            this.portNumber = portNumber;
            if ( portNumber >= 0 )
            {
                createServer ();
            }
        }
        catch ( final Exception e )
        {
            // failed
            logger.warn ( "Failed to set port", e );
        }
    }

    private void destroyServer () throws Exception
    {
        for ( final ProjectStateExporter exporter : this.states.values () )
        {
            exporter.unregister ();
        }

        if ( this.ngpExporter != null )
        {
            this.ngpExporter.stop ();
            this.ngpExporter = null;
        }
        if ( this.hive != null )
        {
            this.hive.stop ();
            this.hive = null;
        }
    }

    private void createServer () throws Exception
    {
        logger.info ( "Creating openSCADA DA server on port: {}", this.portNumber );

        this.hive = new HiveImpl ();
        this.hive.start ();
        this.ngpExporter = new org.openscada.da.server.ngp.Exporter ( this.hive, ConnectionInformation.fromURI ( String.format ( "da:ngp://0.0.0.0:%s", this.portNumber ) ) );
        this.ngpExporter.start ();

        for ( final ProjectStateExporter exporter : this.states.values () )
        {
            exporter.register ( this.hive );
        }
    }

    public void dispose ()
    {
        try
        {
            destroyServer ();
        }
        catch ( final Exception e )
        {
            // failed
            logger.warn ( "Failed to dispose", e );
        }
    }
}
