/*
 * Copyright (c) 2016.  ouyangzn   <ouyangzn@163.com>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ouyangzn.github.module.main;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import butterknife.ButterKnife;
import com.huidr.lib.pickview.TimePickerView;
import com.jakewharton.rxbinding.view.RxView;
import com.jakewharton.rxbinding.widget.TextViewEditorActionEvent;
import com.ouyangzn.github.App;
import com.ouyangzn.github.R;
import com.ouyangzn.github.base.BaseActivity;
import com.ouyangzn.github.base.CommonConstants.ConfigSP;
import com.ouyangzn.github.base.CommonConstants.GitHub;
import com.ouyangzn.github.bean.apibean.Repository;
import com.ouyangzn.github.bean.apibean.SearchResult;
import com.ouyangzn.github.bean.localbean.SearchFactor;
import com.ouyangzn.github.module.common.SearchResultAdapter;
import com.ouyangzn.github.module.main.MainContract.IMainPresenter;
import com.ouyangzn.github.module.main.MainContract.IMainView;
import com.ouyangzn.github.utils.ImageLoader;
import com.ouyangzn.github.utils.Log;
import com.ouyangzn.github.utils.ScreenUtils;
import com.ouyangzn.github.view.InputEdit;
import com.ouyangzn.recyclerview.BaseRecyclerViewAdapter;
import java.util.ArrayList;
import java.util.Date;
import rx.functions.Action1;
import rx.functions.Func1;

import static com.ouyangzn.github.base.CommonConstants.NormalCons.LIMIT_20;

public class MainActivity extends BaseActivity<IMainView, IMainPresenter>
    implements IMainView, NavigationView.OnNavigationItemSelectedListener,
    BaseRecyclerViewAdapter.OnLoadingMoreListener,
    BaseRecyclerViewAdapter.OnRecyclerViewItemClickListener {

  private final int DATA_PER_PAGE = LIMIT_20;

  private View mLoadingView;
  private SwipeRefreshLayout mRefreshLayout;
  private SearchResultAdapter mAdapter;
  private DrawerLayout mDrawerLayout;
  private NavigationView mNavView;
  private SearchFactor mSearchFactor;
  /** 上一次选择的语言item的id */
  private int mPreSelectedId = 0;
  // 重新加载或者加载下一页
  private boolean mIsRefresh = true;
  private int mCurrPage = 1;

  @Override public IMainPresenter initPresenter() {
    return new MainPresenter(this);
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    // 非all语言，不保存创建时间的搜索条件
    if (!GitHub.LANG_ALL.equals(mSearchFactor.language)) {
      mSearchFactor.setCreateDate(null);
    }
    mPresenter.saveSearchFactor(mSearchFactor);
  }

  @Override protected void initData() {
    initSearchFactor();
    mAdapter = new SearchResultAdapter(R.layout.item_search_result, new ArrayList<Repository>(0));
    mAdapter.setOnRecyclerViewItemClickListener(this);
    mAdapter.setOnLoadingMoreListener(this);
    // fixme 如果上一次选的语言是all，退出重进，搜索到的数据不正确，可能是接口的问题，同一个接口，多次调用返回数据不一定相同
    search(false);
  }

  @Override protected void initView(Bundle savedInstanceState) {
    setContentView(R.layout.activity_main);
    setTitle(GitHub.LANG_ALL.equals(mSearchFactor.language) ? getString(R.string.app_name)
        : mSearchFactor.language);

    mLoadingView = ButterKnife.findById(this, R.id.main_loading);
    mLoadingView.setVisibility(View.VISIBLE);

    Toolbar toolbar = ButterKnife.findById(this, R.id.toolbar);
    setSupportActionBar(toolbar);

    // @BindView 找不到，NavigationView下的view直接find也找不到
    mNavView = ButterKnife.findById(this, R.id.nav_view);
    mNavView.setNavigationItemSelectedListener(this);
    initNavView();
    ImageView img_photo = (ImageView) mNavView.getHeaderView(0).findViewById(R.id.img_photo);
    ImageLoader.loadAsCircle(img_photo, R.drawable.ic_photo);

    // @BindView 找不到
    mDrawerLayout = ButterKnife.findById(this, R.id.drawer_layout);
    ActionBarDrawerToggle toggle =
        new ActionBarDrawerToggle(this, mDrawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
    mDrawerLayout.addDrawerListener(toggle);
    toggle.syncState();

    mRefreshLayout = ButterKnife.findById(this, R.id.refreshLayout);
    mRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
      @Override public void onRefresh() {
        search(true);
      }
    });

    final InputEdit inputEdit = ButterKnife.findById(this, R.id.view_search);
    inputEdit.setOnClearTextListener(new InputEdit.OnClearTextListener() {
      @Override public void onClearText() {
        mSearchFactor.keyword = null;
      }
    });
    inputEdit.setOnEditorActionListener(new Action1<TextViewEditorActionEvent>() {
      @Override public void call(TextViewEditorActionEvent actionEvent) {
        if (EditorInfo.IME_ACTION_SEARCH == actionEvent.actionId()) {
          String keyword = inputEdit.getInputText().trim();
          ScreenUtils.hideKeyBoard(inputEdit);
          inputEdit.clearFocus();
          // 关键字不为空 或者 之前搜过,现在清空搜索条件
          if (!TextUtils.isEmpty(keyword) || !TextUtils.isEmpty(mSearchFactor.keyword)) {
            mSearchFactor.keyword = keyword;
            if (!GitHub.LANG_ALL.equals(mSearchFactor.language)) {
              mSearchFactor.setCreateDate(null);
            }
            search(true);
          }
        }
      }
    });
    RecyclerView recyclerView = ButterKnife.findById(this, R.id.recycler);
    recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
    LayoutInflater inflater = getLayoutInflater();
    mAdapter.setLoadMoreView(inflater.inflate(R.layout.item_load_more, recyclerView, false));
    mAdapter.setEmptyView(inflater.inflate(R.layout.item_no_data, recyclerView, false));
    recyclerView.setAdapter(mAdapter);
    RxView.touches(recyclerView, new Func1<MotionEvent, Boolean>() {
      @Override public Boolean call(MotionEvent event) {
        ScreenUtils.hideKeyBoard(inputEdit);
        inputEdit.clearFocus();
        return false;
      }
    }).subscribe();

  }

  @Override public void onBackPressed() {
    if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
      mDrawerLayout.closeDrawer(GravityCompat.START);
    } else {
      super.onBackPressed();
    }
  }

  @Override public boolean onNavigationItemSelected(MenuItem item) {
    mSearchFactor.setCreateDate(null);
    int id = item.getItemId();
    boolean isAllLanguage = false;
    switch (id) {
      case R.id.nav_all: {
        mSearchFactor.language = GitHub.LANG_ALL;
        isAllLanguage = true;
        break;
      }
      case R.id.nav_java: {
        mSearchFactor.language = GitHub.LANG_JAVA;
        break;
      }
      case R.id.nav_oc: {
        mSearchFactor.language = GitHub.LANG_OC;
        break;
      }
      case R.id.nav_swift: {
        mSearchFactor.language = GitHub.LANG_SWIFT;
        break;
      }
      case R.id.nav_c: {
        mSearchFactor.language = GitHub.LANG_C;
        break;
      }
      case R.id.nav_cpp: {
        mSearchFactor.language = GitHub.LANG_CPP;
        break;
      }
      case R.id.nav_php: {
        mSearchFactor.language = GitHub.LANG_PHP;
        break;
      }
      case R.id.nav_js: {
        mSearchFactor.language = GitHub.LANG_JS;
        break;
      }
      case R.id.nav_python: {
        mSearchFactor.language = GitHub.LANG_PYTHON;
        break;
      }
      case R.id.nav_ruby: {
        mSearchFactor.language = GitHub.LANG_RUBY;
        break;
      }
      case R.id.nav_c_sharp: {
        mSearchFactor.language = GitHub.LANG_C_SHARP;
        break;
      }
      case R.id.nav_shell: {
        mSearchFactor.language = GitHub.LANG_SHELL;
        break;
      }
    }
    mDrawerLayout.closeDrawer(GravityCompat.START);
    // 搜索全部语言时，必须有项目创建时间限制，否则搜不到结果
    if (isAllLanguage) {
      TimePickerView pickerView = new TimePickerView(this, TimePickerView.Type.YEAR_MONTH_DAY);
      pickerView.setCancelable(false);
      pickerView.setRange(2006, 2016);
      pickerView.setCyclic(true);
      pickerView.setTime(new Date());
      pickerView.setOnCancelListener(new TimePickerView.OnCancelListener() {
        @Override public void onCancel() {
          // 点取消，回到原来的状态
          mNavView.setCheckedItem(mPreSelectedId);
        }
      });
      pickerView.setOnTimeSelectListener(new TimePickerView.OnTimeSelectListener() {
        @Override public void onTimeSelect(Date date) {
          setTitle(getString(R.string.app_name));
          // 搜索，加入createDate限制
          mSearchFactor.setCreateDate(date);
          search(true);
        }
      });
      pickerView.show();
      return true;
    }
    setTitle(mSearchFactor.language);
    search(true);
    mPreSelectedId = item.getItemId();
    return true;
  }

  @Override public void showProgressDialog() {
  }

  @Override public void dismissProgressDialog() {
  }

  @Override public void showErrorOnQueryData(String tips) {
    toast(tips);
    mLoadingView.setVisibility(View.GONE);
    if (mIsRefresh) mRefreshLayout.setRefreshing(false);
    if (mAdapter.isLoadingMore()) mAdapter.loadMoreFinish(true, null);
  }

  @Override public void showQueryDataResult(SearchResult result) {
    Log.d(TAG, result.toString());
    mLoadingView.setVisibility(View.GONE);
    mCurrPage++;
    boolean hasMore = result.getRepositories().size() == DATA_PER_PAGE;
    mAdapter.setHasMore(hasMore);
    mAdapter.setLanguageVisible(GitHub.LANG_ALL.equals(mSearchFactor.language));
    if (mIsRefresh) {
      mRefreshLayout.setRefreshing(false);
      mAdapter.resetData(result.getRepositories());
    } else {
      if (mAdapter.isLoadingMore()) {
        mAdapter.loadMoreFinish(hasMore, result.getRepositories());
      } else {
        mAdapter.addData(result.getRepositories());
      }
    }
  }

  private void search(boolean isRefresh) {
    if (isRefresh) {
      mCurrPage = 1;
      mRefreshLayout.setRefreshing(true);
    }
    Log.d(TAG, "----------搜索数据:" + mSearchFactor);
    mPresenter.queryData(mSearchFactor, DATA_PER_PAGE, mCurrPage);
    mIsRefresh = isRefresh;
  }

  @Override public void requestMoreData() {
    search(false);
  }

  @Override public void onItemClick(View view, int position) {
    Repository repository = mAdapter.getItem(position);
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setData(Uri.parse(repository.getHtmlUrl()));
    startActivity(intent);
  }

  private void initNavView() {
    if (GitHub.LANG_ALL.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_all);
      mPreSelectedId = R.id.nav_all;
    } else if (GitHub.LANG_JAVA.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_java);
      mPreSelectedId = R.id.nav_java;
    } else if (GitHub.LANG_OC.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_oc);
      mPreSelectedId = R.id.nav_oc;
    } else if (GitHub.LANG_SWIFT.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_swift);
      mPreSelectedId = R.id.nav_swift;
    } else if (GitHub.LANG_C.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_c);
      mPreSelectedId = R.id.nav_c;
    } else if (GitHub.LANG_CPP.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_cpp);
      mPreSelectedId = R.id.nav_cpp;
    } else if (GitHub.LANG_PHP.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_php);
      mPreSelectedId = R.id.nav_php;
    } else if (GitHub.LANG_JS.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_js);
      mPreSelectedId = R.id.nav_js;
    } else if (GitHub.LANG_PYTHON.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_ruby);
      mPreSelectedId = R.id.nav_ruby;
    } else if (GitHub.LANG_C_SHARP.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_c_sharp);
      mPreSelectedId = R.id.nav_c_sharp;
    } else if (GitHub.LANG_SHELL.equals(mSearchFactor.language)) {
      mNavView.setCheckedItem(R.id.nav_shell);
      mPreSelectedId = R.id.nav_shell;
    }
  }

  private void initSearchFactor() {
    SharedPreferences configSp = getSharedPreferences(ConfigSP.SP_NAME, MODE_PRIVATE);
    String searchFactorJson = configSp.getString(ConfigSP.KEY_LANGUAGE, "");
    if (TextUtils.isEmpty(searchFactorJson)) {
      mSearchFactor = new SearchFactor();
      mSearchFactor.language = GitHub.LANG_JAVA;
    } else {
      mSearchFactor = App.getGson().fromJson(searchFactorJson, SearchFactor.class);
    }
  }
}