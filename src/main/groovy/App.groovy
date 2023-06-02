import java.io.File 
import groovy.swing.SwingBuilder 
import javax.swing.* 
import java.awt.* 
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.text.SimpleDateFormat;
import javax.swing.RowSorter;
import javax.print.PrintServiceLookup;
import javax.print.PrintService;
import java.awt.print.PrinterJob;
import javax.print.DocPrintJob;
import javax.print.DocFlavor;
import javax.print.SimpleDoc;
import javax.print.Doc;


// Find the default service
PrintService service = PrintServiceLookup.lookupDefaultPrintService();

def myapp = new SwingBuilder()

// get date for report:
def date = new Date()
sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

def readCodes = {
    // read into list of lists
    def codeList = []
    new File('analysis_codes.txt').splitEachLine("\t") { fields ->
        def (Analyse, Resultat, Flexlab_code, LC_analyse) = [fields[0], fields[1], fields[2], fields[3]]
        codeList.add([Analyse, Resultat, Flexlab_code, LC_analyse])
    }
    return codeList
}
      
// A list of lists containing the key to flexlab codes    
def codeList = readCodes.call()

def readFile = { f -> 
    def map = [:]
    f.readLines() // Skip if sample_name equals ..
    f.each { it -> 
        def( Analysis_Name, sample_Name, results) = [it.split("\t")[1].replace("\"", ""), it.split("\t")[4].replace("\"", ""), it.split("\t")[5].replace("\"", "")]
        map[Analysis_Name + "_" + sample_Name] = [sample_Name, results]
    }
    return map
}

def readSats = { f -> 
    def map = [:]
    f.readLines() // Skip if sample_name equals ..
    f.each { it -> 
        def( sample_number, LID ) = [it.split("\\|")[1], it.split("\\|")[0]]
        map[ sample_number ] = LID
    }
    return map
}

def getCode = { analysis_name, result ->
    // Input analysis name and result, get a flexlab-code in return
    // Iterate code list:
    for ( item in codeList ) {
        if ( item[3].replace("\"", "") == analysis_name && item[1].replace("\"", "") == result ) {
            if ( item[2] == "TEMP" ) { // This is because hemkro is special case where two analyses are combined
                return result
            } else {
                return item[2]
                }
        } else if ( result == "Unknown" )  { // Unknown i resultat skal generere warning
            return "Unknown"
        } 
    }
    return "NOT_FOUND"
}

def getAnalysisCode = { analysis_name ->
    // Input analysis name and result, get a flexlab-code in return
    // Iterate code list:
    for ( item in codeList ) {
        if ( item[3].replace("\"", "") == analysis_name ) {
            return item[0]
        } 
    }
    return "NOT_FOUND"
}

// Lag metode for å sjekke antall prøver SATS mot antall i resultat:
def getMissed = { m1, m2, data -> // Return two maps with samples that are missing from the report
    // list of samples in LC-report not in SATS
    def lc_only = []
    def lc = []
    // List of smaples in SATS not in LC_report:
    def sats_only = []
    // Find samples that have "Negative" as result, and hence is not in the report
    def negatives = []

    // M1 er fra LC, M2 er fra Sats
    // Samples that are in LC restults but not in sats list:
    m1.each { entry -> 
        if ( !m2[ entry.value[0] ]  && !["Sample Name", "NTC", "Hz", "FVB", "FVC", "FIIC", "Pos"].contains( entry.value[0] )) {
            lc_only.add(entry.value[0])
        } else {
            lc.add(entry.value[0])
        }
        if( entry.value[1] == "Negative" && !["Sample Name", "NTC", "Hz", "FVB", "FVC", "FIIC", "Pos"].contains( entry.value[0] ) ) { // Remove those with "Negative"
                negatives.add(entry.value[0])
            }
    }
    
    m2.each { key, value ->
        if (!lc.contains(key)) {
               sats_only.add(key)
        }
    }
    // Remove from sats_only the samples that are present on hemkro of leiden/protromb (analyses with dual samples each):
    data.each { 
        sats_only.removeElement(it["sample_ID"])
    }
    
    return [ lc_only, sats_only, negatives ]
}

def mergeMap = { m1, m2, codes -> // Merge the two maps with the list of codes for flexlab:
    
    // Make a list where the duplicates are merged. e.g. LID=0010099997 sample_ID=[1,2] 
    dupMap = [:]
    m2.each { entry ->
        dupMap[entry.value] = (!dupMap[entry.value])? entry.key : [entry.key, m2.find {it.value == entry.value && it.key != entry.key }?.key]
    }

    final_list = []
    m1.each { entry -> 
        if ( m2[ entry.value[0] ] ) { // sjekker om prøven finnes i LC-rapport & SATSliste
            sid = dupMap.find { it.key == m2[ entry.value[0]] }?.value
            final_list.add([
                sample_ID: ( sid instanceof java.util.ArrayList)? (  getAnalysisCode.call( entry.key.split( "_" )[0] ) == "Leiden" ) ? sid.min(): ( getAnalysisCode.call( entry.key.split( "_" )[0] ) == "Protromb" )? sid.max(): sid.min(): sid,
                LID: m2[ entry.value[0]],
                resultat:getCode.call( entry.key.split( "_" )[0], entry.value[1] ),
                analysis: getAnalysisCode.call( entry.key.split( "_" )[0]),
                date:sdf.format(date) ])
        }  
    }
    return final_list
}

def mergeHemKro = { data -> // reiterate data, combine the two hemkroesults
    def outlist = []
    def final_outlist = []
    // Iterate list:
    data.each { entry -> 
        if ( entry["analysis"] == "Hemkro_C282Y" ) {
            // Finn tilsvarende med H63D
            def H63D = data.find { element ->
                element["analysis"] == "Hemkro_H63D" && element["LID"] == entry["LID"]    
            }
            outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: entry["resultat"], analysis: entry["analysis"], date: entry["date"], C282Y: entry["resultat"], H63D: H63D["resultat"] ]);
        }  else if ( entry["analysis"] == "DYPD_2A" ) {
            // Finn tilsvarende med DYPD_13
            def DYPD_13 = data.find { element ->
                element["analysis"] == "DYPD_13" && element["LID"] == entry["LID"]    
            }
            // Finn tilsvarende med DYPD_rs67
            def DYPD_67 = data.find { element ->
                element["analysis"] == "DYPD_67" && element["LID"] == entry["LID"]    
            }
            // Finn tilsvarende med DYPD_rs56
            def DYPD_56 = data.find { element ->
                element["analysis"] == "DYPD_56" && element["LID"] == entry["LID"]    
            }
            outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: entry["resultat"], analysis: entry["analysis"], date: entry["date"], DYPD_2A: entry["resultat"], DYPD_13: DYPD_13["resultat"], DYPD_67: DYPD_67["resultat"], DYPD_56: DYPD_56["resultat"] ]);
        } else if (entry["analysis"] != "Hemkro_C282Y" && entry["analysis"] != "Hemkro_H63D"  && entry["analysis"] != "DYPD_2A" && entry["analysis"] != "DYPD_13" && entry["analysis"] != "DYPD_67" && entry["analysis"] != "DYPD_56") {
            final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: entry["resultat"], analysis: entry["analysis"], date: entry["date"] ])
        }
    }
    // case switch to get correct code for hemrk
    outlist.each { entry ->
        switch(entry) { 
            case {entry["C282Y"] == "wild type" && entry["H63D"] == "wild type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "GHFE1", analysis: "Hemkro", date: entry["date"] ])
                break
            case {entry["C282Y"] == "heterozygote" && entry["H63D"] == "wild type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "GHFE2", analysis: "Hemkro", date: entry["date"] ])
                break
            case {entry["C282Y"] == "wild type" && entry["H63D"] == "heterozygote"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "GHFE3", analysis: "Hemkro", date: entry["date"] ])
                break
            case {entry["C282Y"] == "heterozygote" && entry["H63D"] == "heterozygote"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "GHFE4", analysis: "Hemkro", date: entry["date"] ])
                break
            case {entry["C282Y"] == "mutant" && entry["H63D"] == "wild type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "GHFE5", analysis: "Hemkro", date: entry["date"] ])
                break
            case {entry["C282Y"] == "wild type" && entry["H63D"] == "mutant"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "GHFE6", analysis: "Hemkro", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD1", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Heterozygote"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD2", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Heterozygote" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD3", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Heterozygote" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD4", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Heterozygote" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD5", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Homo Mutated"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD6", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Heterozygote" && entry["DYPD_56"] == "Heterozygote"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD7", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Homo Mutated" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD8", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Heterozygote" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Heterozygote" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD9", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Heterozygote" && entry["DYPD_67"] == "Heterozygote" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD10", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Heterozygote" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Heterozygote"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD11", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Heterozygote" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Heterozygote"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD12", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Mutated" && entry["DYPD_13"] == "Homo Wild Type" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD13", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Homo Wild Type" && entry["DYPD_13"] == "Homo Mutated" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD14", analysis: "DNADPYD", date: entry["date"] ])
                break
            case {entry["DYPD_2A"] == "Heterozygote" && entry["DYPD_13"] == "Heterozygote" && entry["DYPD_67"] == "Homo Wild Type" && entry["DYPD_56"] == "Homo Wild Type"}:
                final_outlist.add( [sample_ID: entry["sample_ID"], LID: entry["LID"], resultat: "DPYD15", analysis: "DNADPYD", date: entry["date"] ])
                break
        }   
    }
    return final_outlist
}

def OpenReportFromLC = { text ->
    // Sets initial path to project dir
    def initialPath = System.getProperty("user.dir");
    JFileChooser fc = new JFileChooser(initialPath);
    fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
    fc.setDialogTitle(text);
    int result = fc.showOpenDialog( null );
    switch ( result ) {
        case JFileChooser.APPROVE_OPTION:
            File file = fc.getSelectedFile();
            def path =  fc.getCurrentDirectory().getAbsolutePath();
            // Lagt til midlertidig
            return file
        break;
        case JFileChooser.CANCEL_OPTION:
        case JFileChooser.ERROR_OPTION:
            break;
    }
}

def SaveReport = { data ->
    def initialPath = System.getProperty("user.dir");
    JFileChooser fc = new JFileChooser(initialPath);
    fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
    fc.setDialogTitle("Lagre liste");
    int result = fc.showOpenDialog( null );
    switch ( result ) { 
        case JFileChooser.APPROVE_OPTION:
            FileWriter writer = new FileWriter(fc.getSelectedFile());
            data.each {
                writer.write( it["LID"].replaceFirst ("^0*", "") + "\t" + it["resultat"] + "\t" + it["analysis"].toUpperCase() + "\t"+ sdf.format(date) + "\r\n");
                writer.flush();
            }
            writer.close();
            break;
        case JFileChooser.CANCEL_OPTION:
        case JFileChooser.ERROR_OPTION:
            break;
    }
}

def warningpanel = { samples, missing ->
    def printOut = ""
    if (samples["LID"].size() > 0) {
        printOut+="The following samples have \"unkown\" as result:\n" + samples["LID"].join('\n')
        }
    if (missing[0].size() > 0) {
        printOut+= "\nSamples in LC-result not in SATS:\n" + missing[0].join("\n")
        }
    if (missing[1].size() > 0) {
        printOut+= "\nSamples in Sats not in LC-results:\n" + missing[1].join(", ")
        }
    if (missing[2].size() > 0) {
        printOut += "\nThe following samples has \"Negative\" as result:\n${missing[2].join("\n")}"
        }
    if (printOut != "") {
        JOptionPane.showMessageDialog(new JFrame(), printOut  );
    }
}

def warningpanel2 = { lcfile, satsfile ->
    def printOut = "OBS, datoen til satliste og LC-resultatfil er ikke like:\n\nKlikk ok hvis du allikevel vil bruke filene og Cancel for å avbryte\n\n${lcfile}\n${satsfile}\n\n"
    JOptionPane.showConfirmDialog(new JFrame(), printOut, "Sjekk filene", JOptionPane.OK_CANCEL_OPTION)
}

def warningpanel3 = { printernavn ->
    def printOut = "Skriver satt til: ${printernavn}\nØnsker du å skrive ut til denne, velg ok. Ellers avbryt.\n"
    JOptionPane.showConfirmDialog(new JFrame(), printOut, "Skriv ut", JOptionPane.OK_CANCEL_OPTION)
}





// ny frame:
// Create a builder 
def myapp2 = new SwingBuilder()

// Compose the builder 
def myframe2 = { data ->
    myapp2.frame(title : 'AD reporter v2.0',size : [500, 600], defaultCloseOperation : WindowConstants.DISPOSE_ON_CLOSE) {
        panel(layout : new BorderLayout()) {
            scrollPane(constraints : BorderLayout.CENTER) {
                table {
                    tableModel( list : data) {
                        propertyColumn( header: 'Sample ID', propertyName:'sample_ID', editable: false )
                        propertyColumn( header: 'Barcode', propertyName:'LID', editable: false )
                        propertyColumn( header: 'Final Conc', propertyName:'resultat', editable: false )
                        propertyColumn( header: 'Compound', propertyName:'analysis', editable: false )
                        propertyColumn( header: 'Acq Time', propertyName:'date', editable: false )
                    }   
                }
            } 
            panel(constraints : BorderLayout.PAGE_END ) {
                //label(text : '<html>Lagre en rapport fra Ligthcycler:<br/></html>', horizontalAlignment : JLabel.CENTER, constraints : BorderLayout.SOUTH)
                button(text : 'Lagre rapport fra Lightcycler', actionPerformed : {
                    SaveReport.call( data )
                })
                button(text : 'Print rapport fra Lightcycler', actionPerformed : {
                    // Make a string for printing
                    String outStr = ""
                    outStr += "sample_ID\tLID\tresultat\tanalysis\r"
                    outStr += "__________________________________________________\r\n"
                    // Iterate data and add all cols to the print:
                    data.each { fields -> 
                        outStr += "${fields['sample_ID']}\t${fields['LID']}\t${fields['resultat']}\t${fields['analysis']}\r"
                        outStr += "__________________________________________________\r\n"
                    }
                    
                    
                    
                    def checkPrint = warningpanel3.call(service.toString())
                    if (checkPrint != 2) {
                    //Create the print job
                    DocPrintJob job = service.createPrintJob();
                    InputStream is = new ByteArrayInputStream(outStr.getBytes());
                    DocFlavor flavor =  DocFlavor.INPUT_STREAM.AUTOSENSE   ;
                    Doc doc= new SimpleDoc(is, flavor, null);
                    // Print it
                    job.print(doc, null);
                    }
                })
            }
        }
    }
}
	
// Nye forslag:
def process = {
    // Les inn result fra LC
    
    
    def lcFile = OpenReportFromLC.call("Velg rapport fra fra LC")
    // Les inn sats
    def satsFile = OpenReportFromLC.call("Velg SATS")
    
    // Sjekk om dato på sats og resultatfiler er like:
    def lcdate = lcFile.toString() =~ /\\([0-9]*-[0-9]*-[0-9]*)/
    def lcdateFormatted = lcdate[0][1].replace('-', '')
    def satsdate = satsFile.toString() =~ /\\BATCH_([0-9]*)/
    def satsdateFormatted = satsdate[0][1]
    
    if (lcdateFormatted != satsdateFormatted) {
        // Åpne en boks med mulighet for abort hvis fildatoene ikke stemmer overens:
        def checkContinue = warningpanel2.call(lcFile.toString(), satsFile.toString())
        if (checkContinue == 2) {System.exit(1)}
    }
    
    
    // Lag en map bestående av LID + flexlabkode
    def lcmap = readFile.call(lcFile)
    def satsmap = readSats.call(satsFile)
    def data = mergeMap.call( lcmap, satsmap , codeList )
    
    // Combine hemkromresults
    data = mergeHemKro.call(data)
    
    // get the samples that have "unknown" result
    def unknowns = data.findAll {
        it["resultat"] == "Unknown"
        }
    // Get missing samples and the ones with "Negative" as result:
    def missing = getMissed.call(lcmap, satsmap, data)
    
    // sortere:
    data = data.sort ({m1, m2 -> m1.sample_ID.toInteger() <=> m2.sample_ID.toInteger()})
    myframe2(data).setVisible(true)
    if (unknowns.size() > 0 || missing.size() > 0) {
        warningpanel.call(unknowns, missing)
    }
}

def buttonPanel = {
    myapp.panel(constraints : BorderLayout.SOUTH) {
        button(text : 'Åpne fil', actionPerformed : process ) // Endret fra OpenReportFromLC midlertidig
   } 
}  

def mainPanel = {
   myapp.panel(layout : new BorderLayout()) {
      label(text : 'Åpne en rapport fra Ligthcycler', horizontalAlignment : JLabel.CENTER, constraints : BorderLayout.CENTER)
      buttonPanel()   
   }
}  

def myframe = myapp.frame(title : 'AD reporter v2.0', location : [100, 100],
   size : [400, 300], defaultCloseOperation : WindowConstants.EXIT_ON_CLOSE) {
      mainPanel()
   } 
	
myframe.setVisible(true)
