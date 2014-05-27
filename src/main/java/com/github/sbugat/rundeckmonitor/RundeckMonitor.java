package com.github.sbugat.rundeckmonitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.swing.JOptionPane;

import org.rundeck.api.RundeckMonitorClient;
import org.rundeck.api.domain.RundeckEvent;
import org.rundeck.api.domain.RundeckExecution;
import org.rundeck.api.domain.RundeckHistory;

/**
 * Primary and main class of the Rundeck Monitor
 *
 * @author Sylvain Bugat
 *
 */
public class RundeckMonitor implements Runnable {

	private static final String PROPERTIES_FILE = "rundeckMonitor.properties"; //$NON-NLS-1$
	private static final String RUNDECK_FAILED_JOB = "fail"; //$NON-NLS-1$

	private static final String RUNDECK_PROPERTY_URL = "rundeck.monitor.url"; //$NON-NLS-1$
	private static final String RUNDECK_PROPERTY_LOGIN = "rundeck.monitor.login"; //$NON-NLS-1$
	private static final String RUNDECK_PROPERTY_PASSWORD = "rundeck.monitor.password"; //$NON-NLS-1$
	private static final String RUNDECK_PROPERTY_PROJECT = "rundeck.monitor.project"; //$NON-NLS-1$
	private static final String RUNDECK_MONITOR_PROPERTY_NAME = "rundeck.monitor.name"; //$NON-NLS-1$
	private static final String RUNDECK_MONITOR_PROPERTY_REFRESH_DELAY = "rundeck.monitor.refresh.delay"; //$NON-NLS-1$
	private static final String RUNDECK_MONITOR_PROPERTY_EXECUTION_LATE_THRESHOLD = "rundeck.monitor.execution.late.threshold"; //$NON-NLS-1$
	private static final String RUNDECK_MONITOR_PROPERTY_FAILED_JOB_NUMBER = "rundeck.monitor.failed.job.number"; //$NON-NLS-1$
	private static final String RUNDECK_MONITOR_PROPERTY_DATE_FORMAT = "rundeck.monitor.date.format"; //$NON-NLS-1$

	private final String rundeckUrl;
	private final String rundeckLogin;
	private final String rundeckPassword;
	private final String rundeckProject;

	private final String rundeckMonitorName;
	private final int refreshDelay;
	private final int lateThreshold;
	private final int failedJobNumber;
	private final String dateFormat;

	private final RundeckMonitorClient rundeckClient;

	private final RundeckMonitorTrayIcon rundeckMonitorTrayIcon;

	private final RundeckMonitorState rundeckMonitorState= new RundeckMonitorState();

	private Set<Long> knownLateExecutionIds = new LinkedHashSet<>();

	private Set<Long> knownFailedExecutionIds = new LinkedHashSet<>();

	public RundeckMonitor() throws FileNotFoundException, IOException {

		//Configuration loading
		final File propertyFile = new File( PROPERTIES_FILE );
		if( ! propertyFile.exists() ){

			JOptionPane.showMessageDialog( null, "Copy and configure " + PROPERTIES_FILE + " file", PROPERTIES_FILE + " file is missing", JOptionPane.ERROR_MESSAGE ); //$NON-NLS-1$ //$NON-NLS-2$

			System.exit( 1 );
		}

		//Load the configuration file and extract properties
		final Properties prop = new Properties();
		prop.load( new FileInputStream( propertyFile ) );

		rundeckUrl = prop.getProperty( RUNDECK_PROPERTY_URL );
		rundeckLogin = prop.getProperty( RUNDECK_PROPERTY_LOGIN );
		rundeckPassword = prop.getProperty( RUNDECK_PROPERTY_PASSWORD );
		rundeckProject = prop.getProperty( RUNDECK_PROPERTY_PROJECT );

		rundeckMonitorName = prop.getProperty( RUNDECK_MONITOR_PROPERTY_NAME );
		refreshDelay = Integer.parseInt( prop.getProperty( RUNDECK_MONITOR_PROPERTY_REFRESH_DELAY ) );
		lateThreshold = Integer.parseInt( prop.getProperty( RUNDECK_MONITOR_PROPERTY_EXECUTION_LATE_THRESHOLD ) );
		failedJobNumber = Integer.parseInt( prop.getProperty( RUNDECK_MONITOR_PROPERTY_FAILED_JOB_NUMBER ) );
		dateFormat = prop.getProperty( RUNDECK_MONITOR_PROPERTY_DATE_FORMAT );

		//Initialize the rundeck connection
		rundeckClient = new RundeckMonitorClient( rundeckUrl, rundeckLogin, rundeckPassword );

		//Init the tray icon
		rundeckMonitorTrayIcon = new RundeckMonitorTrayIcon( rundeckUrl, rundeckMonitorName, failedJobNumber, dateFormat, rundeckMonitorState );

		//Initialize and update the rundeck monitor failed/late jobs
		updateRundeckHistory( true );
	}

	/**
	 * RundeckMonitor background process method executing the main loop
	 */
	public void run() {

		while( true ){
			try {

				//
				updateRundeckHistory( false );

				try {
					Thread.sleep( refreshDelay * 1000 );
				}
				catch ( final Exception e ) {

					//Nothing to do
				}
			}
			//If an exception is catch, consider the monitor as disconnected
			catch ( final Exception e ) {

				rundeckMonitorState.setDisconnected( true );
				rundeckMonitorTrayIcon.updateTrayIcon();

				try {

					Thread.sleep( refreshDelay * 1000 );
				}
				catch ( final InterruptedException e1) {

					//Nothing to do
				}
			}
		}
	}

	/**
	 * Call Rundeck rest API and update the monitor state and displayed jobs if there are new failed/late jobs
	 *
	 * @param init boolean to indicate if it's the first call to this method for the monitor initialization
	 */
	private void updateRundeckHistory( final boolean init ) {

		//call Rundeck rest API
		final RundeckHistory lastFailedJobs = rundeckClient.getHistory( rundeckProject, RUNDECK_FAILED_JOB, Long.valueOf( failedJobNumber ), Long.valueOf(0) );
		final List<RundeckExecution> currentExecutions= rundeckClient.getRunningExecutions( rundeckProject );

		//Rundeck calls are OK
		rundeckMonitorState.setDisconnected( false );

		final Date currentTime = new Date();

		final List<JobExecutionInfo> listJobExecutionInfo = new ArrayList<>();

		boolean lateExecutionFound = false;

		//Scan runnings jobs to detect if they are late
		for( final RundeckExecution rundeckExecution : currentExecutions ) {

			if( currentTime.getTime() - rundeckExecution.getStartedAt().getTime() > lateThreshold * 1000 ) {
				listJobExecutionInfo.add( new JobExecutionInfo( rundeckExecution.getId(), rundeckExecution.getStartedAt(), rundeckExecution.getDescription(), true ) );

				if( ! knownLateExecutionIds.contains( rundeckExecution.getId() ) ) {

					lateExecutionFound = true;
				}
			}
		}

		rundeckMonitorState.setLateJobs( lateExecutionFound );

		//Add all lasts failed jobs to the list
		for( final RundeckEvent rundeckEvent : lastFailedJobs.getEvents() ) {

			listJobExecutionInfo.add( new JobExecutionInfo( Long.valueOf( rundeckEvent.getExecutionId() ), rundeckEvent.getStartedAt(), rundeckEvent.getTitle(), false ) );

			if( ! knownFailedExecutionIds.contains( rundeckEvent.getExecutionId() ) ) {

				rundeckMonitorState.setFailedJobs( true );
				knownFailedExecutionIds.add( rundeckEvent.getExecutionId() );
			}
		}

		//Display failed/late jobs on the trayIcon menu
		rundeckMonitorTrayIcon.updateExecutionIdsList( listJobExecutionInfo );

		if( init ) {

			rundeckMonitorState.setFailedJobs( false );
		}

		//Update the tray icon color
		rundeckMonitorTrayIcon.updateTrayIcon();
	}

	/**
	 * RundeckMonitor main method
	 *
	 * @param args program arguments: none is expected and used
	 */
	public static void main( final String args[] ){

		try {
			new Thread( new RundeckMonitor() ).start();
		}
		catch ( final Exception e) {

			final StringWriter stringWriter = new StringWriter();
			e.printStackTrace( new PrintWriter( stringWriter ) );
			JOptionPane.showMessageDialog( null, e.getMessage() + System.lineSeparator() + stringWriter.toString(), "RundeckMonitor initialization error", JOptionPane.ERROR_MESSAGE ); //$NON-NLS-1$ //$NON-NLS-2$

			System.exit( 1 );
		}
	}
}