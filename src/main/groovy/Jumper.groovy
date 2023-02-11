package lilive.jumper

import groovy.json.JsonOutput
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import java.awt.Rectangle
import javax.swing.SwingUtilities
import org.freeplane.api.Node
import org.freeplane.core.util.LogUtils
import org.freeplane.plugin.script.proxy.Proxy
import org.freeplane.plugin.script.proxy.ScriptUtils
import lilive.jumper.data.SMap
import lilive.jumper.data.SNode
import lilive.jumper.data.SNodes
import lilive.jumper.search.SearchOptions
import lilive.jumper.search.SearchEngine
import lilive.jumper.search.SearchResultsCollector
import lilive.jumper.settings.LoadedSettings
import lilive.jumper.settings.DisplayResultsSettings
import lilive.jumper.display.components.Color
import lilive.jumper.display.windows.Gui

/**
 * The main class that control the application.
 * Usage: Jumper.start() create an instance and
 * display the GUI. Nothing else to do.
 */
class Jumper implements SearchResultsCollector {
    
    private Proxy.Controller c
    private Node initialNode // Selected node in the map when Jumper starts
    
    // Each node has a corresponding SNode:
    private SMap sMap             // Mimic the Freeplane map, made of SNode
    private SNode initialSNode    // SNode for initialNode
    private SNodes candidates     // The SNodes where to search
    private SNodes results        // The SNodes that match

    SearchEngine searchEngine
    
    private Gui gui
    
    // The search pattern
    private String searchPattern
    // The previously jumped patterns:
    private ArrayList<String> history = []
    private int historyIdx = 0
    private final int historyMaxSize = 200

    // Define the search method (regex, case sensitive, etc)
    private SearchOptions searchOptions = new SearchOptions()

    // Control the nodes where to search:
    final int ALL_NODES = 0
    final int SIBLINGS = 1
    final int DESCENDANTS = 2
    final int SIBLINGS_AND_DESCENDANTS = 3
    private int candidatesType = ALL_NODES
    private boolean discardClones = false
    private boolean discardHiddenNodes = true

    // Used to select a node in the map as the user select one of the results:
    private Node mapSelectedNode // The node to select (and center) in the map
    private ArrayList<Boolean> ancestorsFolding // The folding state of its ancestors
    
    // Node where to jump when jumper close
    private Node jumpToNode

    // Class instance created by start()
    private static Jumper instance
    public static Jumper get(){ return instance }

    
    
    //////////////////////////////////////////////////////////////////
    // Main public functions /////////////////////////////////////////
    //////////////////////////////////////////////////////////////////

    // Start Jumper. Display the GUI. Nothing else to do to start Jumper.
    public static void start(){
        if( instance ) throw new Exception( "Jumper already started" )
        instance = new Jumper()
        instance.init()
    }

    // Jump to the user selected node (if any) and close the GUI
    public void end(){
        searchEngine.stopSearch()
        searchEngine.shutdown()
        saveSettings()
        gui.dispose()
        if( jumpToNode ) selectMapNode( jumpToNode )
        else selectMapNode( initialNode )
        clear()
    }

    public void setSearchPattern( String pattern ){
        if( pattern.equals( searchPattern ) ) return
        searchPattern = pattern
        search()
    }
        
    // Filter the candidates to find the pattern
    public void search(){
        searchEngine.stopSearch()
        clearResults()
        searchEngine.startSearch( candidates, searchPattern, searchOptions, this )
    }

    public Gui getGui(){
        return gui
    }

    // One step backward in patterns history. Update the GUI.
    public void selectPreviousPattern(){
        if( historyIdx <= 0 ) return
        historyIdx--
        gui.setPatternText( history[ historyIdx ] )
    }
    
    // One step forward in patterns history. Update the GUI.
    public void selectNextPattern(){
        if( historyIdx >= history.size() ) return
        historyIdx++
        if( historyIdx == history.size() ) gui.setPatternText( "" )
        else gui.setPatternText( history[ historyIdx ] )
    }
    
    // Try to select the initial selected node in the GUI nodes list.
    public void selectDefaultResult(){
        if( ! results ) return
        int selectIdx = results.findIndexOf{ it == initialSNode }
        if( selectIdx < 0 ) selectIdx = 0
        gui.setSelectedResult( selectIdx )
    }

    // Select a node in the Freeplane map, and center the view around it.
    public void selectMapNode( Node node ){

        if( mapSelectedNode && node == mapSelectedNode ) return

        // Restore folding state of the branch of the previously selected node  
        restoreFolding()

        // Save folding state of the branch of the new selected node
        ancestorsFolding = new ArrayList<Boolean>()
        Node n = node
        while( n = n.parent )
            ancestorsFolding << n.isFolded()
        
        c.select( node )
        c.centerOnNode( node )
        mapSelectedNode = node
    }


    
    //////////////////////////////////////////////////////////////////
    // SearchResultsCollector functions //////////////////////////////
    //////////////////////////////////////////////////////////////////

    public void addResults( SNodes newResults, boolean done ){
        results.addAll( newResults )
        SwingUtilities.invokeLater( new Runnable(){
            public void run() {
                gui.addResults( newResults, candidates.size(), ! done )
                // selectDefaultResult()
            }
        })
    }
    
    public void clearResults(){
        results.clear()
        SwingUtilities.invokeLater( new Runnable(){
            public void run() {
                gui.clearResults()
            }
        })
    }

    
    
    //////////////////////////////////////////////////////////////////
    // Options functions /////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////

    // Define which nodes to search in
    // (all the map ? siblings of selected node ? etc)
    public void setCandidatesType( int type ){
        int previous = candidatesType
        candidatesType = type
        gui.updateOptions()
        if( previous != type ){
            updateCandidates()
            search()
        }
    }

    public int getCandidatesType(){
        return candidatesType
    }

    // Use regex search ?
    public void setRegexSearch( boolean value ){
        boolean previous = searchOptions.useRegex
        searchOptions.useRegex = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Use case sentitive search ?
    public void setCaseSensitiveSearch( boolean value ){
        boolean previous = searchOptions.caseSensitive
        searchOptions.caseSensitive = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Search patterns only at the beginning of the texts ?
    public void setSearchFromStart( boolean value ){
        boolean previous = searchOptions.fromStart
        searchOptions.fromStart = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Break the search string into multiple units ?
    public void setSplitPattern( boolean value ){
        boolean previous = searchOptions.splitPattern
        searchOptions.splitPattern = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Search pattern units across branches ?
    public void setTransversalSearch( boolean value ){
        boolean previous = searchOptions.transversal
        searchOptions.transversal = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Search in nodes details ?
    public void setDetailsSearch( boolean value ){
        boolean previous = searchOptions.useDetails
        searchOptions.useDetails = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Search in nodes notes ?
    public void setNoteSearch( boolean value ){
        boolean previous = searchOptions.useNote
        searchOptions.useNote = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Search in nodes attributes names ?
    public void setAttributesNameSearch( boolean value ){
        boolean previous = searchOptions.useAttributesName
        searchOptions.useAttributesName = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Search in nodes attributes values ?
    public void setAttributesValueSearch( boolean value ){
        boolean previous = searchOptions.useAttributesValue
        searchOptions.useAttributesValue = value
        gui.updateOptions()
        if( previous != value ) search()
    }

    // Search un all nodes details (details, notes, attributes) ?
    public void setAllDetailsSearch( boolean check ){
        searchOptions.with{
            if( check && allDetailsTrue() ) return
            if( ! check && allDetailsFalse() ) return
            useDetails         = check
            useNote            = check
            useAttributesName  = check
            useAttributesValue = check
            gui.updateOptions()
            search()
        }
    }

    public SearchOptions getSearchOptions(){
        return searchOptions
    }

    // Search only once in clones.
    // (not such a good choice  with transversal search)
    public void setDiscardClones( boolean value ){
        boolean previous = discardClones
        discardClones = value
        gui.updateOptions()
        if( previous != value ){
            updateCandidates()
            search()
        }
    }

    public boolean getDiscardClones(){
        return discardClones
    }

    // Do not search the nodes hidden because of Freeplane filters
    public void setDiscardHiddenNodes( boolean value ){
        boolean previous = discardHiddenNodes
        discardHiddenNodes = value
        gui.updateOptions()
        if( previous != value ){
            updateCandidates()
            search()
        }
    }

    public boolean getDiscardHiddenNodes(){
        return discardHiddenNodes
    }


    
    //////////////////////////////////////////////////////////////////
    // Private functions /////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////

    // Private constructor. The unique instance is created by start()
    private Jumper(){

        Thread.setDefaultUncaughtExceptionHandler(
            new Thread.UncaughtExceptionHandler(){
                @Override
                public void uncaughtException( Thread t, Throwable e ){
                    LogUtils.warn( "Jumper error: ${e}")
                    e.printStackTrace()
                }
            }
        )
        
        initialNode = ScriptUtils.node()
        c = ScriptUtils.c()

        sMap = new SMap( initialNode.map.root )
        initialSNode = sMap.find{ it.node == initialNode }

        candidates = new SNodes()
        results = new SNodes()
        searchEngine = new SearchEngine()
    }
    
    // Initialize a new instance of Jumper and display the GUI.
    private void init(){

        LoadedSettings settings = loadSettings()

        gui = new Gui( settings )
        updateCandidates()
        if( gui.drs.recallLastPattern ){
            recallLastPattern(
                settings.currentPattern,
                settings.saveTime,
                gui.drs.lastPatternDuration
            )
        }

        gui.show()
    }

    private void clear(){
        instance = null
    }

    // Save all options and state for the new Jumper.start()
    private void saveSettings(){
        
        File file = getSettingsFile()
        
        DisplayResultsSettings drs = gui.drs
        
        Map data = [
            candidatesType     : candidatesType,
            discardClones      : discardClones,
            discardHiddenNodes : discardHiddenNodes,
            history            : history,
            currentPattern     : gui.getPatternText() ?: null,
            saveTime           : System.currentTimeMillis() / 1000,
            searchOptions      : searchOptions,
            gui                : gui.getSaveMap()
        ]

        try{ 
            JsonGenerator.Options options = new JsonGenerator.Options()
            options.addConverter( DisplayResultsSettings ){
                DisplayResultsSettings settings, String key ->
                settings.toMap()
            }
            options.addConverter( Color ){
                Color color, String key ->
                color.toString()
            }
            JsonGenerator generator = options.build()
            String json = generator.toJson( data )
            file.write( JsonOutput.prettyPrint( json ) )
        } catch( Exception e){
            LogUtils.warn( "Jumper: unable to save the settings : $e")
        }
    }

    // Load all options and states from the last Jumper
    private LoadedSettings loadSettings(){

        if( gui ) throw new Exception( "Load settings before gui creation" )
        
        LoadedSettings settings = new LoadedSettings()
        
        File file = getSettingsFile()
        if( ! file.exists() ) return settings

        settings.winBounds = new Rectangle()
        try{
            Map s = new JsonSlurper().parseText( file.text )
            candidatesType = s.candidatesType ?: candidatesType
            if( s.discardClones      != null ) discardClones            = s.discardClones
            if( s.discardHiddenNodes != null ) discardHiddenNodes       = s.discardHiddenNodes
            if( s.searchOptions      != null ) searchOptions            = new SearchOptions( s.searchOptions )
            if( s.currentPattern     != null ) settings.currentPattern  = s.currentPattern
            if( s.saveTime           != null ) settings.saveTime        = s.saveTime
            history = s.history ?: history
            if( s.gui ) s.gui.with{
                if( showOptions != null ) settings.showOptions = showOptions
                if( drs ) settings.drs = DisplayResultsSettings.fromMap( drs )
                settings.winBounds.x      = rect?.x      ?: 0
                settings.winBounds.y      = rect?.y      ?: 0
                settings.winBounds.width  = rect?.width  ?: 0
                settings.winBounds.height = rect?.height ?: 0
            }
        } catch( Exception e){
            LogUtils.warn( "Jumper: unable to load the settings : $e")
        }

        historyIdx = history.size()
        if( settings.winBounds.width <= 0 ) settings.winBounds = null

        return settings
    }

    // Return the file used to load and save the settings
    private File getSettingsFile(){
        File file = new File( c.getUserDirectory().toString() + File.separator + 'lilive_jumper.json' )
    }

    // Update the candidates, according to the selected options.
    private void updateCandidates(){

        if( ! initialSNode ) return
        if( sMap == null ) return

        switch( candidatesType ){
            case ALL_NODES:
                candidates = sMap.getAllNodes()
                break
            case SIBLINGS:
                candidates = sMap.getSiblingsNodes( initialSNode )
                break
            case DESCENDANTS:
                candidates = sMap.getDescendantsNodes( initialSNode )
                break
            case SIBLINGS_AND_DESCENDANTS:
                candidates = sMap.getSiblingsAndDescendantsNodes( initialSNode )
                break
        }
        if( discardClones      ) removeClones( candidates )
        if( discardHiddenNodes ) removeHiddenNodes( candidates )
    }

    /**
     * Keep only one clone for each node.
     * If a node has some clones, keep the one at the minimal level
     * with the minimal ID
     */
    private void removeClones( SNodes sNodes ){

        // Compare 2 nodes by level than by ID
        Comparator firstClone = {
            Node a, Node b ->
            int d1 = a.getNodeLevel( true )
            int d2 = b.getNodeLevel( true )
            if( d1 < d2 ) return -1
            if( d1 > d2 ) return 1
            if( a.id < b.id ) return -1
            if( a.id > b.id ) return 1
            return 0
        }
        
        sNodes.removeAll{
            SNode sNode ->
            ArrayList<Node> clones = sNode.node.getNodesSharingContent()
            if( clones.size() == 0 ) return false
            clones = clones.clone()
            clones << sNode.node
            clones.sort( firstClone )
            if( sNode.node != clones[0] ) return true
        }
    }

    // Keep only visibles nodes.
    private void removeHiddenNodes( SNodes sNodes ){
        sNodes.removeAll{
            SNode sNode ->
            ! sNode.node.isVisible()
        }
    }

    // Add a pattern to history. If already in, put it at most recent position.
    private void addToHistory( String pattern ){
        if( ! pattern ) return
        history.remove( pattern )
        history << pattern
        if( history.size() > historyMaxSize ) history = history[ (-historyMaxSize)..-1]
    }

    /**
     * Fill the pattern text field with its value when Jumper was closed.
     * @param pattern The text that was in the text field when Jumper was closed.
     * @param patternTime The time in seconds when Jumper was closed.
     * @param patternDuration Do not touch the text field if more than this number
     *        of seconds has past since Jumper was closed.
     */
    private void recallLastPattern(
        String pattern,
        Integer patternTime,
        int patternDuration
    ){
        if( ! pattern ) return
        if(
            patternTime != null
            && patternDuration != 0
            && System.currentTimeMillis() / 1000 > patternTime + patternDuration
        ){
            return
        }
        if( history && history.last() == pattern ) selectPreviousPattern()
        else gui.setPatternText( pattern )
    }

    // Restore folding state of the branch of selected node in the view,
    // before it was selected by selectMapNode()
    private void restoreFolding(){
        if( mapSelectedNode ){
            Node n = mapSelectedNode
            while( n = n.parent ) n.setFolded( ancestorsFolding.pop() )
        }
    }

    // Select jumpToNode, center the view around it, and close Jumper.
    private void jumpToSelectedResult(){
        int idx = gui.getSelectedResult()
        if( idx >= 0 ){
            addToHistory( gui.getPatternText() )
            jumpToNode = results[ idx ].node
            end()
        }
    }
    
}
