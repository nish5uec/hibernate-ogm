package com.iampfac.demo.config;

import java.util.Properties;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;

import org.hibernate.cfg.Environment;
import org.hibernate.ogm.cfg.OgmProperties;
import org.hibernate.ogm.datastore.neo4j.Neo4jProperties;
import org.hibernate.ogm.datastore.neo4j.query.parsing.impl.Neo4jAliasResolver;
import org.hibernate.ogm.jpa.HibernateOgmPersistence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

@Configuration
@ComponentScan("com.iampfac.demo")
@EnableJpaRepositories("com.iampfac.demo.data.jpa")
public class ApplicationConfiguration {

	private Properties hibProperties() {
		Properties properties = new Properties();
		properties.put( Environment.HBM2DDL_AUTO, "none" );
		properties.put( OgmProperties.DATASTORE_PROVIDER, "neo4j_http");
		properties.put( OgmProperties.HOST, "localhost:7777" );
		properties.put( OgmProperties.USERNAME, "neo4j" );
		properties.put( OgmProperties.PASSWORD, "neo4j" );
//		properties.put( Neo4jProperties.DATABASE_PATH, "/home/ddalto/workspace/projects/hibernate-ogm/demo-spring-data-jpa-hibernate-h2/target/NEO4J" );
		return properties;
	}

	// @Bean
	// public DataSource dataSource() {
	// return new EmbeddedDatabaseBuilder().setType( EmbeddedDatabaseType.H2 ).build();
	// }
	//
	@Bean
	public JpaVendorAdapter jpaVendorAdapter() {
		HibernateJpaVendorAdapter bean = new HibernateJpaVendorAdapter();
		bean.setGenerateDdl( true );
		return bean;
	}

	@Bean
	public JpaTransactionManager transactionManager(EntityManagerFactory emf) {
		return new JpaTransactionManager( emf );
	}

	@Bean
	public LocalContainerEntityManagerFactoryBean entityManagerFactory() {
		String persistenceUnitName = "60Capital-neo4j-ejbPU";

		LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
		factory.setJpaVendorAdapter( jpaVendorAdapter() );
		factory.setPackagesToScan( "com.iampfac.demo" );
		factory.setPersistenceUnitName( persistenceUnitName );
		factory.setJpaProperties( hibProperties() );
		factory.setPersistenceProviderClass( HibernateOgmPersistence.class );
		return factory;
	}
}
