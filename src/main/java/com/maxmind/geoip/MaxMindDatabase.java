/*
 * @author Daniel Einspanjer and Doug Moran
 * @since  April-16-2008
 * 
 */package com.maxmind.geoip;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;

import org.apache.commons.vfs2.FileObject;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.exception.KettlePluginException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaFactory;
import org.pentaho.di.core.vfs.KettleVFS;

import com.google.common.net.InetAddresses;
import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.model.DomainResponse;
import com.maxmind.geoip2.model.IspResponse;
import com.maxmind.geoip2.DatabaseReader;

/*
 * Encapsulates metadata about the individual MaxMind database files.  
 * 
 * The main thing I'm trying to do is keep selectedFields in each sub class as an array of the 
 * enum type in order to have the running code be fast and type safe for the individual subclass.
 * 
 * Also wanted to simply capture the metadata about each field in 1 place only.  It adds a little design 
 * complexity initially, but should make adding new databases and fields easy.
 * 
 */
public abstract class MaxMindDatabase {
  DatabaseReader lookupService = null;

  // TODO: Default values needs to handle non string types for long and lat on city DB.  Convert this to object and 
  // have the setter verify the type and do the conversion so it is ready at runtime.
  String[] defaultValues = null;
  String[] overrideFieldNames = null;

  /*
   * Returns info about all database fields in the database
   */
  public abstract IMaxmindMetaInterface[] getAllFields();

  /**
   * Called by the Kettle Engine to add a new row to the stream.  This baby should execute as fast as possible.
   * 
   * @param outputRow One row of data, the size of the array is assumed to be set and the implementation 
   * of this method is assumed to not violate that size
   * @param firstNewFieldIndex The index of the outputRow (column) to start adding the data to
   * @param ip The ip address to lookup
 * @throws GeoIp2Exception 
 * @throws IOException 
   */
  public abstract void getRowData(Object[] outputRow, int firstNewFieldIndex, String ip) throws IOException, GeoIp2Exception;

  /**
   * Sets the fields (columns) and metadata that will be returned when getRowData() is called.  Each of these arrays 
   * should be the same size and positionally reference the same field.
   * 
   * @param selectedFieldNames The list of field names - should match the name of the enum it represents
   * @param overrideFieldNames The field names to return for each database field.  Null or empty values 
   * will return the name of the enum as the field name.
   * @param defaultValues value to return if the IP addr is not found or the data is empty
   */
  public void setSelectedFields(String[] selectedFieldNames, String[] overrideFieldNames, String[] defaultValues) {
    this.defaultValues = defaultValues;
    this.overrideFieldNames = overrideFieldNames;
    setSelectedFields(selectedFieldNames);
  }

  /**
   * Used by subclasses to set the selected fields.  Needed an override so the subclasses can use the actual enum type
   * to store the selected values.
   * 
   * @param selectedFieldNames
   */
  abstract void setSelectedFields(String[] selectedFieldNames);

  /**
   * returns metadata about the selected fields.
   * @return array of IMaxmindMetaInterface class for each selected field
   */
  public abstract IMaxmindMetaInterface[] getSelectedFields();

  /**
   * returns String array of fieldnames available fro this database.
   * @return Text version Field Names
   */
  public final String[] getFieldNames() {
    IMaxmindMetaInterface[] fields = getAllFields();
    String[] fNames = new String[fields.length];

    for (int i = 0; i < fields.length; ++i) {
      fNames[i] = fields[i].toString();
    }

    return (fNames);
  }

  /**
   * Called by Kettle engine to set up metadata about the data row that will be returned
   * @param r Row Metadata
   * @param origin Step Origin 
   */
  public final void getFields(RowMetaInterface r, String origin) {
    IMaxmindMetaInterface[] selectedFelds = getSelectedFields();
    for ( int i = 0; i < selectedFelds.length; ++i ) {
      ValueMetaInterface v = selectedFelds[i].getValueMetadata();
      v.setName( Const.isEmpty( overrideFieldNames[i] ) ? selectedFelds[i].toString(): overrideFieldNames[i] );
      v.setOrigin(origin);
      r.addValueMeta(v);
    }
  }

  /**
   * Sets that location on the filesystem of the MaxMind Database
   * 
   * @param dbLocation String representing the path of the database file used by File class
   * @throws IOException
 * @throws KettleFileException 
   */
  public void setDbLocation(String dbLocation) throws IOException, KettleFileException {
    FileObject dbFile = KettleVFS.getFileObject(dbLocation);
    InputStream dbStream = KettleVFS.getInputStream(dbFile);
    lookupService = new DatabaseReader.Builder(dbStream).withCache(new CHMCache()).build();
  }

  /**
   * TODO: I'd like to add a user option for "Strict IP lookup" using InetAddr in case the input 
   * data is not perfect.  This method assumes a well formed ip address.
   * 
   * This is a heavily optimized method for extracting the long representation of the IP
   * address without creating several Regex or String objects for each call.
   * @param ip
   * @return
   */
  /*public final long getAddressFromIpV4(String ip) {
    if (ip == null)
      return 0;
    int length = ip.length();
    long result = 0;
    short blockNumber = 0;
    long block = 0;
    for (int i = 0; i < length; i++) {
      char c = ip.charAt(i);
      if (c == '.') {
        result += block << ((3 - blockNumber) * 8);
        blockNumber++;
        block = 0;
      } else {
        block = block * 10 + c - '0';
      }
    }
    result += block << ((3 - blockNumber) * 8);
    return result;
  }*/
  public final InetAddress getAddressFromIpV4(String ip) {
    return InetAddresses.forString(ip);
  }
  
  public DatabaseReader getLookupService() {
    return lookupService;
  }
}

/*
 * Used to get metadata out of the enums
 */
interface IMaxmindMetaInterface {
  public ValueMetaInterface getValueMetadata();
}


/**
 * 
 * The following enums encapsulate metadata about the maxmind datasources.  The design was to have the 
 * fastest and safest runtime experience.  To make it easy to add fields and databases as the databases change
 * 
 * Not sure if these should be in different files.  Since they are only used here, I thought this was easiest to
 * mintain.
 *
 */

/***********************************************************************************************
 * Maxmind City Database
 */
class MaxMindCityData extends MaxMindDatabase {
  enum CityFields implements IMaxmindMetaInterface {
    country_code(ValueMetaInterface.TYPE_STRING, 2, 0) {
      Object getVal(CityResponse l) {
        return (l.getCountry().getIsoCode());
      }
    },
    country_name(ValueMetaInterface.TYPE_STRING, 50, 0) {
      Object getVal(CityResponse l) {
        return (l.getCountry().getName());
      }
    },
    region_code(ValueMetaInterface.TYPE_STRING, 2, 0) {
      Object getVal(CityResponse l) {
        return (l.getMostSpecificSubdivision().getIsoCode());
      }
    },
    region_name(ValueMetaInterface.TYPE_STRING, 50, 0) {
      Object getVal(CityResponse l) {
        return (l.getMostSpecificSubdivision().getName());
      }
    },
    city_name(ValueMetaInterface.TYPE_STRING, 255, 0) {
      Object getVal(CityResponse l) {
        return (l.getCity().getName());
      }
    },
    latitude(ValueMetaInterface.TYPE_NUMBER, 10, 4) {
      Object getVal(CityResponse l) {
        return ((double) l.getLocation().getLatitude());
      }
    },
    longitude(ValueMetaInterface.TYPE_NUMBER, 10, 4) {
      Object getVal(CityResponse l) {
        return ((double) 
            l.getLocation().getLongitude());
      }
    },
    timezone(ValueMetaInterface.TYPE_STRING, 255, 0) {
      Object getVal(CityResponse l) {
        return (l.getLocation().getTimeZone());
      }
    };

    ValueMetaInterface valueMeta;

    private CityFields(int type, int length, int precision) {
      try {
        this.valueMeta = ValueMetaFactory.createValueMeta(this.name(), type, length, precision);
      } catch (KettlePluginException e) {
        this.valueMeta = null;
      }
    }

    public ValueMetaInterface getValueMetadata() {
      return (valueMeta);
    }

    abstract Object getVal(CityResponse l);
  }

  CityFields[] selectedFields = new CityFields[0];

  @Override
  public void setSelectedFields(String[] fieldNames) {
    selectedFields = (fieldNames == null) ? new CityFields[0] : new CityFields[fieldNames.length];
    for (int i = 0; i < selectedFields.length; ++i) {
      selectedFields[i] = CityFields.valueOf(fieldNames[i]);
    }
  }

  @Override
  public IMaxmindMetaInterface[] getSelectedFields() {
    return (selectedFields);
  }

  @Override
  public IMaxmindMetaInterface[] getAllFields() {
    return (CityFields.values());
  }

  @Override
  public void getRowData(Object[] outputRow, int firstNewFieldIndex, String ip) throws IOException, GeoIp2Exception {
	InetAddress ipAddr = getAddressFromIpV4(ip);
	CityResponse location = null;
	try {
      location = getLookupService().city(ipAddr);
	} catch (AddressNotFoundException e) {
	  location = null; //Ignore, use default values
	}
    if (location != null) {
      Object o;
      for ( int i = 0; i < selectedFields.length; ++i) {
        o = selectedFields[i].getVal(location);
        outputRow[firstNewFieldIndex++] = ( o == null ) ? defaultValues[i] : o ;
      }
    } else {
      for (String field : defaultValues) {
        outputRow[firstNewFieldIndex++] = field;
      }
    }
  }
}

/***********************************************************************************************
 * Maxmind Country Database
 */
class MaxMindCountryData extends MaxMindDatabase {
  enum CountryFields implements IMaxmindMetaInterface {
    country_code(ValueMetaInterface.TYPE_STRING, 2, 0) {
      Object getVal(CountryResponse co) {
        return (co.getCountry().getIsoCode());
      }
    },
    country_name(ValueMetaInterface.TYPE_STRING, 50, 0) {
      Object getVal(CountryResponse co) {
        return (co.getCountry().getName());
      }
    };

    ValueMetaInterface valueMeta;

    private CountryFields(int type, int length, int precision) {
      try {
        this.valueMeta = ValueMetaFactory.createValueMeta(this.name(), type, length, precision);
      } catch (KettlePluginException e) {
        this.valueMeta = null;
      }
    }

    public ValueMetaInterface getValueMetadata() {
      return (valueMeta);
    }

    abstract Object getVal(CountryResponse co);
  }

  CountryFields[] selectedFields = new CountryFields[0];

  @Override
  public void setSelectedFields(String[] fieldNames) {
    selectedFields = (fieldNames == null) ? new CountryFields[0] : new CountryFields[fieldNames.length];
    for (int i = 0; i < selectedFields.length; ++i) {
      selectedFields[i] = CountryFields.valueOf(fieldNames[i]);
    }
  }

  @Override
  public IMaxmindMetaInterface[] getSelectedFields() {
    return (selectedFields);
  }

  @Override
  public IMaxmindMetaInterface[] getAllFields() {
    return (CountryFields.values());
  }

  @Override
  public void getRowData(Object[] outputRow, int firstNewFieldIndex, String ip) throws IOException, GeoIp2Exception {
    CountryResponse co;
	try {
      co = getLookupService().country(getAddressFromIpV4(ip));
	} catch (AddressNotFoundException e) {
      co = null; // Ignore, use default values
	}
    if (co != null) {
      Object o;
      for ( int i = 0; i < selectedFields.length; ++i) {
        o = selectedFields[i].getVal(co);
        outputRow[firstNewFieldIndex++] = ( o == null ) ? defaultValues[i] : o ;
      }
    } else {
      for (String field : defaultValues) {
        outputRow[firstNewFieldIndex++] = field;
      }
    }
  }
}

/***********************************************************************************************
 * Maxmind ISP Database
 */
class MaxMindIspData extends MaxMindDatabase {
  enum IspFields implements IMaxmindMetaInterface {
    isp_name(ValueMetaInterface.TYPE_STRING, 255, 0) {
      Object getVal(IspResponse isp) {
        return (isp.getIsp());
      } // yes, this is correct, they use Org for both ISP and Org DB
    };

    ValueMetaInterface valueMeta;

    private IspFields(int type, int length, int precision) {
      try {
        this.valueMeta = ValueMetaFactory.createValueMeta(this.name(), type, length, precision);
      } catch (KettlePluginException e) {
        this.valueMeta = null;
      }
    }

    public ValueMetaInterface getValueMetadata() {
      return (valueMeta);
    }

    abstract Object getVal(IspResponse isp);
  }

  IspFields[] selectedFields = new IspFields[0];

  @Override
  public void setSelectedFields(String[] fieldNames) {
    selectedFields = (fieldNames == null) ? new IspFields[0] : new IspFields[fieldNames.length];
    for (int i = 0; i < selectedFields.length; ++i) {
      selectedFields[i] = IspFields.valueOf(fieldNames[i]);
    }
  }

  @Override
  public IMaxmindMetaInterface[] getSelectedFields() {
    return (selectedFields);
  }

  @Override
  public IMaxmindMetaInterface[] getAllFields() {
    return (IspFields.values());
  }

  @Override
  public void getRowData(Object[] outputRow, int firstNewFieldIndex, String ip) throws IOException, GeoIp2Exception {
    Object o;
    IspResponse isp = null;
    try {
      isp = getLookupService().isp(getAddressFromIpV4(ip));
    } catch (AddressNotFoundException e) {
      isp = null; //Ignore, use default values
    }
    for ( int i = 0; i < selectedFields.length; ++i) {
      o = selectedFields[i].getVal(isp);
      outputRow[firstNewFieldIndex++] = (o == null) ? defaultValues[i] : o;
    }
  }
}

/***********************************************************************************************
 * Maxmind Organization Database
 */
class MaxMindOrgData extends MaxMindDatabase {
  enum OrgFields implements IMaxmindMetaInterface {
    organization_name(ValueMetaInterface.TYPE_STRING, 255, 0) {
      Object getVal(IspResponse isp) {
        return (isp.getOrganization());
      }
    };

    ValueMetaInterface valueMeta;

    private OrgFields(int type, int length, int precision) {
      try {
        this.valueMeta = ValueMetaFactory.createValueMeta(this.name(), type, length, precision);
      } catch (KettlePluginException e) {
        this.valueMeta = null;
      }
    }

    public ValueMetaInterface getValueMetadata() {
      return (valueMeta);
    }

    abstract Object getVal(IspResponse isp);
  }

  OrgFields[] selectedFields = new OrgFields[0];

  @Override
  public void setSelectedFields(String[] fieldNames) {
    selectedFields = (fieldNames == null) ? new OrgFields[0] : new OrgFields[fieldNames.length];
    for (int i = 0; i < selectedFields.length; ++i) {
      selectedFields[i] = OrgFields.valueOf(fieldNames[i]);
    }
  }

  @Override
  public IMaxmindMetaInterface[] getSelectedFields() {
    return (selectedFields);
  }

  @Override
  public IMaxmindMetaInterface[] getAllFields() {
    return (OrgFields.values());
  }

  @Override
  public void getRowData(Object[] outputRow, int firstNewFieldIndex, String ip) throws IOException, GeoIp2Exception {
    Object o;
    IspResponse isp = null;
    try {
      isp = getLookupService().isp(getAddressFromIpV4(ip));
    } catch (AddressNotFoundException e) {
      isp = null; //Ignore, use default values
    }
    for ( int i = 0; i < selectedFields.length; ++i) {
      o = selectedFields[i].getVal(isp);
      outputRow[firstNewFieldIndex++] = (o == null) ? defaultValues[i] : o;
    }
  }
}


/***********************************************************************************************
 * Maxmind Domain Database
 */
class MaxMindDomainData extends MaxMindDatabase {
  enum DomainFields implements IMaxmindMetaInterface {
    domain_name(ValueMetaInterface.TYPE_STRING, 255, 0) {
      Object getVal(DomainResponse domain) {
        return (domain.getDomain());
      }
    };

    ValueMetaInterface valueMeta;

    private DomainFields(int type, int length, int precision) {
      try {
        this.valueMeta = ValueMetaFactory.createValueMeta(this.name(), type, length, precision);
      } catch (KettlePluginException e) {
        this.valueMeta = null;
	  }
    }

    public ValueMetaInterface getValueMetadata() {
      return (valueMeta);
    }

    abstract Object getVal(DomainResponse domain);
  }

  DomainFields[] selectedFields = new DomainFields[0];

  @Override
  public void setSelectedFields(String[] fieldNames) {
    selectedFields = (fieldNames == null) ? new DomainFields[0] : new DomainFields[fieldNames.length];
    for (int i = 0; i < selectedFields.length; ++i) {
      selectedFields[i] = DomainFields.valueOf(fieldNames[i]);
    }
  }

  @Override
  public IMaxmindMetaInterface[] getSelectedFields() {
    return (selectedFields);
  }

  @Override
  public IMaxmindMetaInterface[] getAllFields() {
    return (DomainFields.values());
  }

  @Override
  public void getRowData(Object[] outputRow, int firstNewFieldIndex, String ip) throws IOException, GeoIp2Exception {
    Object o;
    InetAddress ipAddr = getAddressFromIpV4(ip);
    DomainResponse domain = null;
    try {
      domain = getLookupService().domain(ipAddr);
    } catch (AddressNotFoundException e ) {
      domain = null; //Ignore, use default values
    }
    for ( int i = 0; i < selectedFields.length; ++i) {
      o = selectedFields[i].getVal(domain);
      outputRow[firstNewFieldIndex++] = (o == null) ? defaultValues[i] : o;
    }
  }
}